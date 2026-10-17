package net.velocity.agent;

import java.util.Objects;
import java.util.function.Consumer;

import net.velocity.agent.state.ExecutionSnapshot;
import net.velocity.agent.state.TrustedScope;

/**
 * The trusted verifiers an execution's saved authority must pass before it runs, resumes or dispatches: the
 * registered account behind its scope, and the saved schedule or monitoring association it claims. Each
 * verifier throws {@link SecurityException} (or a persistence {@code NotFoundException}) when the authority is
 * invalid. The defaults refuse scheduled and monitoring work until the owning feature installs its verifier.
 */
final class ExecutionAuthority {

    private volatile Consumer<TrustedScope> account = TrustedScope::requireOwnerAccountId;
    private volatile Consumer<ExecutionSnapshot> scheduled = snapshot -> {
        throw new SecurityException("Schedule association verifier unavailable");
    };
    private volatile Consumer<ExecutionSnapshot> monitoring = snapshot -> {
        throw new SecurityException("Monitoring association verifier unavailable");
    };

    void setAccount(Consumer<TrustedScope> validator) {
        account = Objects.requireNonNull(validator);
    }

    void setScheduled(Consumer<ExecutionSnapshot> validator) {
        scheduled = Objects.requireNonNull(validator);
    }

    void setMonitoring(Consumer<ExecutionSnapshot> validator) {
        monitoring = Objects.requireNonNull(validator);
    }

    Consumer<ExecutionSnapshot> scheduled() {
        return scheduled;
    }

    Consumer<ExecutionSnapshot> monitoring() {
        return monitoring;
    }

    /** The scope must belong to a registered Velocity account. */
    void verifyAccount(TrustedScope scope) {
        account.accept(scope);
    }

    /** The account first, then every saved association the snapshot claims. */
    void verify(ExecutionSnapshot snapshot) {
        account.accept(snapshot.scope);
        verifyAssociations(snapshot);
    }

    /** The saved schedule association of scheduler-admitted work, then the monitoring association if any. */
    void verifyAssociations(ExecutionSnapshot snapshot) {
        if ("scheduler".equals(snapshot.scope.origin())) {
            scheduled.accept(snapshot);
        }
        if (snapshot.monitoring != null) {
            monitoring.accept(snapshot);
        }
    }
}
