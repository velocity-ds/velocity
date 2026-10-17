package net.velocity.agent.spi;

import net.velocity.agent.ComposedContextHooks;
import net.velocity.agent.state.ExecutionSnapshot;
import net.velocity.agent.state.TrustedScope;

/**
 * A small, additive piece of trusted context composed once by {@link ComposedContextHooks}.
 * Contributors never replace the base hooks' retrieval, history or message preparation; they add
 * trusted guidance and react to lifecycle events. They cannot modify the scope or authorize calls.
 */
public interface ContextContributor {

    /** Trusted guidance appended after the base hooks' supplemental instructions; null or blank adds none. */
    default String supplementalInstructions(TrustedScope scope) {
        return null;
    }

    default void completed(ExecutionSnapshot snapshot) {
    }

    default void deleted(String executionId) {
    }
}
