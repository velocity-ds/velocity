package net.velocity.agent.state;

/** A trusted ingress identity used for durable duplicate detection. */
public record SourceTurn(String workspaceId, String channelId, String threadId, String messageId) {

    public SourceTurn {
        if (workspaceId == null || workspaceId.isBlank() || channelId == null || channelId.isBlank()
                || threadId == null || threadId.isBlank() || messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("Source turn fields must not be blank");
        }
    }
}
