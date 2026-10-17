package net.velocity.agent;

import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

import net.velocity.agent.persistence.ExecutionRepository;
import net.velocity.agent.state.*;
import net.velocity.inference.objects.InferenceResponse;

/** Shared durable accounting and terminal transitions for conversation and tool work. */
final class ExecutionProgress {

    private final ExecutionRepository repository;
    private final ChildRunRepository childRepository;
    private final LongSupplier defaultOutputAllowance;
    private final Predicate<String> externallySettled;
    private final BudgetPolicy deadlineOnly;

    /** For child work that never runs the conversation loop; the task token ceiling is disabled. */
    ExecutionProgress(ExecutionRepository repository, LongSupplier defaultOutputAllowance) {
        this(repository, defaultOutputAllowance, id -> true, 0);
    }

    ExecutionProgress(
            ExecutionRepository repository,
            LongSupplier defaultOutputAllowance,
            Predicate<String> externallySettled,
            long taskTokenCeiling) {
        this.repository = repository;
        this.childRepository = repository instanceof ChildRunRepository child ? child : null;
        this.defaultOutputAllowance = defaultOutputAllowance;
        this.externallySettled = externallySettled;
        this.deadlineOnly = new DeadlineOnlyPolicy(taskTokenCeiling);
    }

    void stop(String id, ExecutionStatus status, String reason) {
        var stopped = update(id, s -> {
            String terminalReason = s.partialReporting != null
                    && (status == ExecutionStatus.partial || status == ExecutionStatus.failed)
                            ? s.partialReporting.reason()
                            : reason;
            s.status = s.cancellationRequested
                    ? ExecutionStatus.cancelled
                    : s.partialReporting != null && status == ExecutionStatus.failed
                            ? ExecutionStatus.partial
                            : status;
            s.failure = terminalReason;
            if (s.status == ExecutionStatus.partial && s.finalAnswer == null) {
                s.finalAnswer = "Work stopped at the " + terminalReason
                        + " boundary. Saved evidence and tool artifacts remain available.";
            }
            if (s.status == ExecutionStatus.blocked && !s.blockers.contains(terminalReason)) {
                s.blockers.add(terminalReason);
            }
            s.pendingQuestions.clear();
        });
        AgentMetrics.taskStopped(stopped.status, childRepository == null);
    }

    void checkpoint(String id, long previous, long began) {
        update(
                id,
                s -> s.activeMillis = Math.max(
                        s.activeMillis,
                        previous + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)));
    }

    private ExecutionSnapshot update(String id, Consumer<ExecutionSnapshot> update) {
        return repository.update(id, -1, update);
    }

    void recordUsage(String id, String source, InferenceResponse.Usage usage, boolean missing) {
        // FileChannel writes reject an already-interrupted thread. Preserve the cancellation signal,
        // but allow observable accounting to reach disk before this external operation settles.
        boolean interrupted = Thread.interrupted();
        try {
            try {
                if (childRepository != null) {
                    childRepository.observe(source, usage, missing);
                } else {
                    update(id, s -> {
                        UsageAccounting.observe(s, source, usage, missing);
                        if (!missing && usage != null) {
                            s.providerReservations.remove(source);
                        }
                    });
                }
            } catch (RuntimeException failure) {
                boolean cancelledWrite = false;
                for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                    cancelledWrite |= cause instanceof java.nio.channels.ClosedByInterruptException;
                }
                if (!cancelledWrite) {
                    throw failure;
                }
                interrupted |= Thread.interrupted();
                // A cancellation that arrived during the write is safe to retry: the source's
                // durable high-water mark makes this mutation idempotent even after a saved write.
                if (childRepository != null) {
                    childRepository.observe(source, usage, missing);
                } else {
                    update(id, s -> {
                        UsageAccounting.observe(s, source, usage, missing);
                        if (!missing && usage != null) {
                            s.providerReservations.remove(source);
                        }
                    });
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    long remaining(ExecutionSnapshot snapshot) {
        long remaining = Math.max(0, snapshot.limits.activeMillis() - snapshot.activeMillis);
        if (childRepository == null) {
            return remaining;
        }
        return childRepository.remainingMillis();
    }

    /** Ordinary tasks and their children share the root deadline and the task token ceiling. */
    BudgetPolicy budget(ExecutionSnapshot snapshot) {
        return snapshot.usesTaskDeadlineOnly() ? deadlineOnly : CumulativeLimitsPolicy.INSTANCE;
    }

    long remainingTokens(ExecutionSnapshot snapshot) {
        return budget(snapshot).remainingTokens(snapshot, () -> charged(snapshot));
    }

    /** Future cancellation alone is insufficient: an interrupted provider or descendant may still be running. */
    boolean awaitSettlement(String id, CancellationHandle cancellation, long previous, long began)
            throws InterruptedException {
        if (cancellation.settledForCaller() && externallySettled.test(id)) {
            cancellation.check();
            return remaining(repository.get(id)) > 0;
        }
        while (!cancellation.settledForCaller() || !externallySettled.test(id)) {
            cancellation.check();
            checkpoint(id, previous, began);
            var snapshot = repository.get(id);
            if (snapshot.cancellationRequested) {
                throw new CancellationException();
            }
            long remaining = remaining(snapshot);
            if (remaining <= 0) {
                return false;
            }
            Thread.sleep(Math.min(10, remaining));
        }
        checkpoint(id, previous, began);
        return remaining(repository.get(id)) > 0;
    }

    long charged(ExecutionSnapshot snapshot) {
        if (childRepository == null) {
            return rootCharged(snapshot);
        }
        long reserved = childRepository.child().reservedTokens;
        return reserved > 0
                ? ContextSizingPolicy.reserve(snapshot.totalTokens, reserved)
                : snapshot.totalTokens;
    }

    private static long rootCharged(ExecutionSnapshot root) {
        long reserved = root.delegatedReservedTokens;
        if (root.providerReservations != null) {
            for (long value : root.providerReservations.values()) {
                reserved = ContextSizingPolicy.reserve(reserved, value);
            }
        }
        return reserved > 0
                ? ContextSizingPolicy.reserve(root.totalTokens, reserved)
                : root.totalTokens;
    }

    /**
     * Tokens charged against the task token ceiling: the root execution's total, which already includes
     * child usage and unsettled reservations. A child run reads it from the root document; a child of a
     * root that is not an ordinary task reports 0, so it can never reach the ceiling.
     */
    long taskCharged(ExecutionSnapshot snapshot) {
        if (childRepository == null) {
            return charged(snapshot);
        }
        var root = childRepository.root();
        return root.usesTaskDeadlineOnly() ? rootCharged(root) : 0;
    }

    int modelRequests(ExecutionSnapshot snapshot) {
        return childRepository == null
                ? snapshot.modelTurns + snapshot.delegatedModelRequests
                : childRepository.child().providerRequests;
    }

    int toolDispatches(ExecutionSnapshot snapshot) {
        return snapshot.toolCalls + snapshot.delegatedToolCalls;
    }

    /**
     * The output tokens the next request may generate: the pinned binding's maximum, as the budget policy
     * caps it.
     */
    long outputAllowance(ExecutionSnapshot snapshot) {
        var binding = snapshot.activeInferenceBinding();
        return childRepository == null && binding != null
                ? budget(snapshot).outputAllowance(snapshot, binding.maxOutputTokens())
                : defaultOutputAllowance.getAsLong();
    }

}
