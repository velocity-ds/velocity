package net.velocity.agent;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;

import net.velocity.agent.state.ExecutionSnapshot;
import net.velocity.agent.state.SourceMessage;
import net.velocity.agent.state.SourceTurn;
import net.velocity.inference.objects.InferenceContent;
import net.velocity.inference.objects.InferenceMessage;

/** Merges durable external identities without reordering native tool call/result pairs. */
final class SourceContext {

    private SourceContext() {
    }

    static void append(ExecutionSnapshot state, List<SourceMessage> messages) {
        var known = new HashSet<SourceTurn>();
        state.sourceMessages.forEach(message -> known.add(message.source()));
        for (var message : messages.stream().sorted((a, b) -> compare(a.source(), b.source())).toList()) {
            if (!known.add(message.source())) {
                continue;
            }
            // Admission and corrections without source messages store the raw user turn; do not duplicate it.
            if (rawTurnIndex(state, message.source()) < 0) {
                int insertion = state.messages.size();
                for (var next : state.sourceMessages.stream()
                        .filter(existing -> compare(message.source(), existing.source()) < 0)
                        .sorted((a, b) -> compare(a.source(), b.source())).toList()) {
                    int index = renderedIndex(state, next);
                    if (index >= 0) {
                        insertion = index;
                        break;
                    }
                }
                if (state.sourceTurn != null && compare(message.source(), state.sourceTurn) < 0) {
                    int original = rawTurnIndex(state, state.sourceTurn);
                    if (original >= 0) {
                        insertion = Math.min(insertion, original);
                    }
                }
                // Insert relative to known external turns. Native assistant/tool messages
                // retain their relative order, including every call/result pair.
                state.messages.add(insertion, message.toMessage());
            }
            state.sourceMessages.add(message);
        }
        state.sourceMessages.sort((a, b) -> compare(a.source(), b.source()));
    }

    private static int renderedIndex(ExecutionSnapshot state, SourceMessage source) {
        var expected = source.toMessage().content().getFirst();
        for (int index = 0; index < state.messages.size(); index++) {
            var message = state.messages.get(index);
            if (message.role() == InferenceMessage.Role.USER && !message.content().isEmpty()
                    && expected.equals(message.content().getFirst())) {
                return index;
            }
        }
        return rawTurnIndex(state, source.source());
    }

    private static int rawTurnIndex(ExecutionSnapshot state, SourceTurn source) {
        String rawText = source.equals(state.sourceTurn)
                ? state.originalRequest
                : state.corrections.stream()
                        .filter(
                                correction -> source.equals(correction.sourceTurn())
                                        && correction.sourceMessages().isEmpty())
                        .map(correction -> correction.text()).findFirst().orElse(null);
        if (rawText == null) {
            return -1;
        }
        for (int index = 0; index < state.messages.size(); index++) {
            var message = state.messages.get(index);
            if (message.role() == InferenceMessage.Role.USER && !message.content().isEmpty()
                    && message.content().getFirst() instanceof InferenceContent.Text text
                    && text.text().equals(rawText)) {
                return index;
            }
        }
        return -1;
    }

    private static int compare(SourceTurn left, SourceTurn right) {
        try {
            return new BigDecimal(left.messageId()).compareTo(new BigDecimal(right.messageId()));
        } catch (NumberFormatException ignored) {
            return left.messageId().compareTo(right.messageId());
        }
    }
}
