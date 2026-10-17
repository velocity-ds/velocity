package net.velocity.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The identity of the one account the console and the operator API act as. Both values blank selects the
 * built-in console-only identity; both set binds the console to the Slack workspace and user of the person who
 * owns the installation, so Slack messages from that person and the console share one account.
 */
@ConfigurationProperties("agent.local-account")
public record LocalAccountProperties(String slackWorkspaceId, String slackUserId) {

    /** Workspace and user of the console-only account. Slack issues its own IDs, so they never collide. */
    public static final String LOCAL_WORKSPACE_ID = "local";
    public static final String LOCAL_USER_ID = "operator";

    public LocalAccountProperties {
        slackWorkspaceId = normalize(slackWorkspaceId);
        slackUserId = normalize(slackUserId);
        if (slackWorkspaceId.isEmpty() != slackUserId.isEmpty()) {
            throw new IllegalArgumentException(
                    "agent.local-account.slack-workspace-id and agent.local-account.slack-user-id must be set together");
        }
        if (slackWorkspaceId.length() > 256 || slackUserId.length() > 256) {
            throw new IllegalArgumentException(
                    "agent.local-account identities must be at most 256 characters");
        }
    }

    /** Whether the console shares its account with a Slack user. */
    public boolean slackLinked() {
        return !slackWorkspaceId.isEmpty();
    }

    public String workspaceId() {
        return slackLinked() ? slackWorkspaceId : LOCAL_WORKSPACE_ID;
    }

    public String userId() {
        return slackLinked() ? slackUserId : LOCAL_USER_ID;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
