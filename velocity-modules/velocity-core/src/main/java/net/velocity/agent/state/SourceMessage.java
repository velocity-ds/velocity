package net.velocity.agent.state;

import java.util.List;
import java.util.Objects;

import net.velocity.inference.SystemPrompts;
import net.velocity.inference.objects.InferenceMessage;

/** External conversation data. Author identity is provenance, never authorization. */
public record SourceMessage(
        SourceTurn source,
        String authorId,
        String botId,
        String text,
        boolean alreadyRepresented) {

    public SourceMessage {
        Objects.requireNonNull(source, "Source message identity");
        if (authorId == null || authorId.isBlank()) {
            throw new IllegalArgumentException("Source author is required");
        }
        Objects.requireNonNull(text, "Source message text");
    }

    public SourceMessage(SourceTurn source, String authorId, String botId, String text) {
        this(source, authorId, botId, text, false);
    }

    public InferenceMessage toMessage() {
        String provenance = SystemPrompts.SLACK_THREAD_CONTEXT.formatted(
                source.workspaceId(),
                source.channelId(),
                source.threadId(),
                source.messageId(),
                authorId,
                botId == null ? "" : " bot_id=" + botId);
        return InferenceMessage.user(
                provenance + "\n" + (alreadyRepresented
                        ? SystemPrompts.SLACK_DELIVERY_ALREADY_REPRESENTED
                        : text));
    }

    public static void validateThread(TrustedScope scope, SourceTurn turn, List<SourceMessage> messages) {
        if (turn != null && (!turn.workspaceId().equals(scope.workspaceId())
                || !turn.channelId().equals(scope.channelId()))) {
            throw new IllegalArgumentException("Source turn must match trusted workspace and channel");
        }
        if (!messages.isEmpty() && turn == null) {
            throw new IllegalArgumentException("Source messages require a triggering source turn");
        }
        for (var message : messages) {
            var source = message.source();
            if (!source.workspaceId().equals(turn.workspaceId())
                    || !source.channelId().equals(turn.channelId())
                    || !source.threadId().equals(turn.threadId())) {
                throw new IllegalArgumentException("Source messages must belong to the triggering thread");
            }
        }
        if (!messages.isEmpty() && messages.stream().noneMatch(message -> message.source().equals(turn))) {
            throw new IllegalArgumentException("Source history must include the triggering message");
        }
    }
}
