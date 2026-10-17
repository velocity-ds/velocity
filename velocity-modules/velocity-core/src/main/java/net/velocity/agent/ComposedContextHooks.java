package net.velocity.agent;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import net.velocity.agent.spi.ContextContributor;
import net.velocity.agent.state.ExecutionSnapshot;
import net.velocity.agent.state.SourceTurn;
import net.velocity.agent.state.TrustedScope;
import net.velocity.inference.objects.InferenceMessage;

/**
 * The base {@link ContextHooks} plus an ordered list of {@link ContextContributor}s, composed once in
 * configuration. This replaces the former chain of forwarding decorators.
 *
 * <p>Application order, which is part of the contract:
 *
 * <ul>
 *   <li>{@code instructions}, {@code history}, {@code prepare}, {@code started} and {@code close} belong
 *       to the base hooks alone; contributors are never consulted.
 *   <li>{@code supplementalInstructions}: the base hooks' text first, then each contributor's text in
 *       list order. Each piece is appended after a newline, except that a null or blank accumulated
 *       text is replaced by the piece (and a null or blank piece adds nothing). When no contributor
 *       adds text the base hooks' value is returned unchanged.
 *   <li>{@code completed} and {@code deleted}: the base hooks run first, then each contributor in list
 *       order. Every later step runs in a {@code finally} of the one before it, so a failure never
 *       skips the remaining steps; if several fail, the exception of the last failing step propagates.
 * </ul>
 *
 * <p>The production composition is every {@code ContextContributor} bean in Spring order: the ten guidance
 * contributors, then the browser session contributor (see {@code docs/agent-gateway.md} for the order values).
 */
public final class ComposedContextHooks implements ContextHooks {

    private final ContextHooks base;
    private final List<ContextContributor> contributors;

    public ComposedContextHooks(ContextHooks base, List<ContextContributor> contributors) {
        this.base = Objects.requireNonNull(base);
        this.contributors = List.copyOf(contributors);
    }

    @Override
    public String instructions(TrustedScope scope) {
        return base.instructions(scope);
    }

    @Override
    public String supplementalInstructions(TrustedScope scope) {
        String text = base.supplementalInstructions(scope);
        for (ContextContributor contributor : contributors) {
            String piece = contributor.supplementalInstructions(scope);
            if (piece == null || piece.isBlank()) {
                continue;
            }
            text = text == null || text.isBlank() ? piece : text + "\n" + piece;
        }
        return text;
    }

    @Override
    public List<InferenceMessage> history(TrustedScope scope, SourceTurn turn) {
        return base.history(scope, turn);
    }

    @Override
    public List<InferenceMessage> prepare(ExecutionSnapshot snapshot, List<InferenceMessage> messages) {
        return base.prepare(snapshot, messages);
    }

    @Override
    public void started(ExecutionSnapshot snapshot) {
        base.started(snapshot);
    }

    @Override
    public void completed(ExecutionSnapshot snapshot) {
        try {
            base.completed(snapshot);
        } finally {
            runContributors(0, contributor -> contributor.completed(snapshot));
        }
    }

    @Override
    public void deleted(String executionId) {
        try {
            base.deleted(executionId);
        } finally {
            runContributors(0, contributor -> contributor.deleted(executionId));
        }
    }

    @Override
    public void close() {
        base.close();
    }

    private void runContributors(int index, Consumer<ContextContributor> step) {
        if (index >= contributors.size()) {
            return;
        }
        try {
            step.accept(contributors.get(index));
        } finally {
            runContributors(index + 1, step);
        }
    }
}
