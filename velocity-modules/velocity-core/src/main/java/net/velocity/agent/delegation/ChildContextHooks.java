package net.velocity.agent.delegation;

import net.velocity.agent.state.TrustedScope;

/**
 * Opt-in trusted child guidance. Root history, retrieval and lifecycle hooks are not invoked for children.
 * Implementations must return trusted policy only, never source text, assignments or retrieved content.
 */
@FunctionalInterface
public interface ChildContextHooks {

    String instructions(TrustedScope scope, String profileName);

    static ChildContextHooks empty() {
        return (scope, profileName) -> null;
    }
}
