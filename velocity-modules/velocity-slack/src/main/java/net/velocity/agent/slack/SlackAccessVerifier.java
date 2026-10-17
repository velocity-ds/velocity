package net.velocity.agent.slack;

import net.velocity.agent.state.TrustedScope;

/** Must verify both current app access and current initiating-user access. Failure denies the operation. */
@FunctionalInterface
public interface SlackAccessVerifier {

    void verify(TrustedScope scope);
}
