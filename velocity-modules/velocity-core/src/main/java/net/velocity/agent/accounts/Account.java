package net.velocity.agent.accounts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Stable internal identity; Slack names, channels, bots and execution origins are not identity. */
public record Account(
        String id,
        String slackWorkspaceId,
        String slackUserId,
        Instant createdAt,
        Instant updatedAt) {

    public Account {
        if (id == null || !UUID.fromString(id).toString().equals(id)) {
            throw new IllegalArgumentException("Invalid account ID");
        }
        requireIdentity(slackWorkspaceId);
        requireIdentity(slackUserId);
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(updatedAt);
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("Invalid account timestamps");
        }
    }

    static void requireIdentity(String value) {
        if (value == null || value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException("Invalid Slack identity");
        }
    }
}
