package net.velocity.agent.state;

/** Admission authority supplied only by a trusted adapter, never by model content. */
public record TrustedScope(
        String workspaceId,
        String requesterId,
        String channelId,
        String botId,
        String origin,
        String ownerAccountId) {

    /** Unowned transport context, used before verification or for installation administration. */
    public TrustedScope(
            String workspaceId,
            String requesterId,
            String channelId,
            String botId,
            String origin) {
        this(workspaceId, requesterId, channelId, botId, origin, null);
    }

    public TrustedScope {
        require(workspaceId, "workspaceId");
        require(requesterId, "requesterId");
        require(channelId, "channelId");
        require(botId, "botId");
        require(origin, "origin");
        if (ownerAccountId != null
                && !java.util.UUID.fromString(ownerAccountId).toString().equals(ownerAccountId)) {
            throw new IllegalArgumentException("Invalid ownerAccountId");
        }
    }

    public String requireOwnerAccountId() {
        if (ownerAccountId == null) {
            throw new SecurityException("A verified Velocity account is required");
        }
        return ownerAccountId;
    }

    public boolean sameOwner(TrustedScope other) {
        return ownerAccountId != null && other != null && ownerAccountId.equals(other.ownerAccountId);
    }

    public TrustedScope withOrigin(String value) {
        return new TrustedScope(workspaceId, requesterId, channelId, botId, value, ownerAccountId);
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
