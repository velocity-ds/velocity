package net.velocity.agent.state;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;

import net.velocity.inference.objects.InferenceContent;
import net.velocity.inference.objects.InferenceMessage;

/** Canonical message identity maintenance, committed with the original native conversation. */
public final class MessageHistory {

    private MessageHistory() {
    }

    public static void prepare(ExecutionSnapshot before, ExecutionSnapshot after) {
        if (before != null && before.messages.equals(after.messages)) {
            after.messageMetadata = new ArrayList<>(before.messageMetadata);
            after.historyGeneration = before.historyGeneration;
            return;
        }
        long generation = before == null ? 1 : Math.addExact(before.historyGeneration, 1);
        var result = new ArrayList<MessageMetadata>();
        var used = new HashSet<Integer>();
        for (int i = 0; i < after.messages.size(); i++) {
            InferenceMessage message = after.messages.get(i);
            MessageMetadata prior = null;
            if (before != null) {
                for (int j = 0; j < before.messages.size(); j++) {
                    if (!used.contains(j) && before.messages.get(j).equals(message)) {
                        used.add(j);
                        prior = before.messageMetadata.get(j);
                        break;
                    }
                }
            }
            SourceTurn source = prior == null ? source(after, message) : prior.source();
            Instant timestamp = prior == null ? sourceTime(after, source) : prior.timestamp();
            if (prior == null && source == null && (before != null || i == after.messages.size() - 1)) {
                timestamp = after.updatedAt;
            }
            result.add(
                    new MessageMetadata(
                            prior == null ? id(after.executionId, generation + ":" + i) : prior.messageId(),
                            i,
                            message.role().name(),
                            source,
                            timestamp));
        }
        after.messageMetadata = result;
        after.historyGeneration = generation;
    }

    private static String id(String execution, String key) {
        return UUID.nameUUIDFromBytes((execution + ":message:" + key).getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    private static SourceTurn source(ExecutionSnapshot state, InferenceMessage message) {
        if (message.content().isEmpty() || message.role() != InferenceMessage.Role.USER) {
            return null;
        }
        var first = message.content().getFirst();
        for (var source : state.sourceMessages) {
            if (source.toMessage().content().getFirst().equals(first)) {
                return source.source();
            }
        }
        if (first instanceof InferenceContent.Text text) {
            if (text.text().equals(state.originalRequest)) {
                return state.sourceTurn;
            }
            for (var correction : state.corrections) {
                if (text.text().equals(correction.text())) {
                    return correction.sourceTurn();
                }
            }
        }
        return null;
    }

    private static Instant sourceTime(ExecutionSnapshot state, SourceTurn source) {
        if (source == null || !"slack".equals(state.scope.origin())) {
            return null;
        }
        try {
            var seconds = new java.math.BigDecimal(source.messageId());
            long whole = seconds.longValueExact();
            return Instant.ofEpochSecond(whole);
        } catch (RuntimeException ignored) {
            try {
                var nanos = new java.math.BigDecimal(source.messageId()).movePointRight(9).longValueExact();
                return Instant.ofEpochSecond(
                        Math.floorDiv(nanos, 1_000_000_000),
                        Math.floorMod(nanos, 1_000_000_000));
            } catch (RuntimeException unknown) {
                return null;
            }
        }
    }

    public static void validate(ExecutionSnapshot state) {
        if (state.messageMetadata == null || state.messageMetadata.size() != state.messages.size()
                || state.historyGeneration < 0) {
            throw new IllegalArgumentException("Invalid message metadata");
        }
        var ids = new HashSet<String>();
        for (int i = 0; i < state.messageMetadata.size(); i++) {
            var item = state.messageMetadata.get(i);
            if (item == null || item.messageId() == null || !ids.add(item.messageId()) || item.sequence() != i
                    || !state.messages.get(i).role().name().equals(item.role())
                    || item.source() != null
                            && (!state.scope.workspaceId().equals(item.source().workspaceId())
                                    || !state.scope.channelId().equals(item.source().channelId()))) {
                throw new IllegalArgumentException("Invalid message identity");
            }
            UUID.fromString(item.messageId());
        }
    }
}
