package net.velocity.agent.accounts;

import java.time.Instant;
import java.util.Optional;

/** Registration callers must first verify the Slack workspace and user. */
public interface AccountRepository {

    Optional<Account> find(String id);

    Account resolveOrCreateSlack(String workspaceId, String userId, Instant now);

    default void verify(net.velocity.agent.state.TrustedScope scope) {
        var account = find(scope.requireOwnerAccountId())
                .orElseThrow(() -> new SecurityException("Unknown Velocity account"));
        if (!account.slackWorkspaceId().equals(scope.workspaceId())
                || !account.slackUserId().equals(scope.requesterId())) {
            throw new SecurityException("Account authority does not match trusted identity");
        }
    }
}
