package net.velocity.agent;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import net.velocity.agent.state.ExecutionSnapshot;

/**
 * Ordinary user tasks: the active-time deadline and the optional total-token ceiling stop work.
 * Other counters remain accounting, not execution cutoffs, so every cumulative check answers
 * "no limit".
 */
final class DeadlineOnlyPolicy implements BudgetPolicy {

    private final long taskTokenCeiling;

    /** @param taskTokenCeiling charged tokens at which the task stops; {@code 0} disables the ceiling */
    DeadlineOnlyPolicy(long taskTokenCeiling) {
        this.taskTokenCeiling = taskTokenCeiling;
    }

    @Override
    public boolean tokenCeilingReached(ExecutionSnapshot snapshot, LongSupplier taskCharged) {
        return taskTokenCeiling > 0 && taskCharged.getAsLong() >= taskTokenCeiling;
    }

    @Override
    public long remainingTokens(ExecutionSnapshot snapshot, LongSupplier charged) {
        return Long.MAX_VALUE;
    }

    @Override
    public long outputAllowance(ExecutionSnapshot snapshot, long maxOutputTokens) {
        return maxOutputTokens;
    }

    @Override
    public boolean modelBudgetExhausted(ExecutionSnapshot snapshot, RunFacts facts) {
        return false;
    }

    @Override
    public boolean finalReportRequired(ExecutionSnapshot snapshot, ExecutionProgress progress) {
        return false;
    }

    @Override
    public boolean finalReportReserveRequired(
            ExecutionSnapshot snapshot,
            ExecutionProgress progress,
            long estimatedInputTokens) {
        return false;
    }

    @Override
    public boolean tokensExhausted(ExecutionSnapshot snapshot, LongSupplier charged) {
        return false;
    }

    @Override
    public boolean recordedTokensExhausted(ExecutionSnapshot snapshot) {
        return false;
    }

    @Override
    public boolean deductsElapsedTime(boolean rootRun) {
        return rootRun;
    }

    @Override
    public boolean toolCallsExceeded(ExecutionSnapshot snapshot, int dispatches, int requested) {
        return false;
    }

    @Override
    public long capToolTimeout(ExecutionSnapshot snapshot, long definitionTimeoutMillis, long timeout) {
        return timeout;
    }

    @Override
    public boolean unusableResponsesExhausted(ExecutionSnapshot snapshot, BooleanSupplier repairing) {
        return false;
    }
}
