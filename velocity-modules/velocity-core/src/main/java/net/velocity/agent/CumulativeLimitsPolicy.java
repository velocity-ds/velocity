package net.velocity.agent;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import net.velocity.agent.state.ExecutionSnapshot;

/**
 * Monitoring and learning executions: cumulative model-request, tool-call and token limits, with a
 * tools-disabled final-report turn and a reserve for it. The output reserve in the limits caps what one
 * request may generate, so the reserve is sized by what a turn can actually emit rather than by the
 * provider's configured output maximum.
 */
final class CumulativeLimitsPolicy implements BudgetPolicy {

    static final CumulativeLimitsPolicy INSTANCE = new CumulativeLimitsPolicy();

    private CumulativeLimitsPolicy() {
    }

    @Override
    public boolean tokenCeilingReached(ExecutionSnapshot snapshot, LongSupplier taskCharged) {
        return false;
    }

    @Override
    public long remainingTokens(ExecutionSnapshot snapshot, LongSupplier charged) {
        return Math.max(0, snapshot.limits.totalTokens() - charged.getAsLong());
    }

    @Override
    public long outputAllowance(ExecutionSnapshot snapshot, long maxOutputTokens) {
        long reserve = snapshot.limits.outputReserve();
        return reserve > 0 ? Math.min(reserve, maxOutputTokens) : maxOutputTokens;
    }

    @Override
    public boolean modelBudgetExhausted(ExecutionSnapshot snapshot, RunFacts facts) {
        return facts.modelRequests().getAsLong() >= snapshot.limits.modelTurns()
                || facts.chargedTokens().getAsLong() >= snapshot.limits.totalTokens();
    }

    @Override
    public boolean finalReportRequired(ExecutionSnapshot snapshot, ExecutionProgress progress) {
        return progress.toolDispatches(snapshot) >= snapshot.limits.toolCalls()
                || progress.modelRequests(snapshot) + 1 >= snapshot.limits.modelTurns()
                || progress.charged(snapshot) >= Math.max(0, snapshot.limits.totalTokens() - 2048);
    }

    /**
     * The remaining budget must cover this request and a final report of the same size, both at the output
     * allowance.
     */
    @Override
    public boolean finalReportReserveRequired(
            ExecutionSnapshot snapshot,
            ExecutionProgress progress,
            long estimatedInputTokens) {
        long requestReserve = ContextSizingPolicy
                .reserve(estimatedInputTokens, progress.outputAllowance(snapshot));
        long remainingBudget = Math.max(0, snapshot.limits.totalTokens() - progress.charged(snapshot));
        return remainingBudget - requestReserve < requestReserve;
    }

    @Override
    public boolean tokensExhausted(ExecutionSnapshot snapshot, LongSupplier charged) {
        return charged.getAsLong() >= snapshot.limits.totalTokens();
    }

    @Override
    public boolean recordedTokensExhausted(ExecutionSnapshot snapshot) {
        return snapshot.totalTokens >= snapshot.limits.totalTokens();
    }

    @Override
    public boolean deductsElapsedTime(boolean rootRun) {
        return true;
    }

    @Override
    public boolean toolCallsExceeded(ExecutionSnapshot snapshot, int dispatches, int requested) {
        return dispatches + requested > snapshot.limits.toolCalls();
    }

    @Override
    public long capToolTimeout(ExecutionSnapshot snapshot, long definitionTimeoutMillis, long timeout) {
        long capped = Math.min(
                timeout,
                definitionTimeoutMillis > 0 ? definitionTimeoutMillis : snapshot.limits.toolTimeoutMillis());
        if (snapshot.monitoring != null) {
            capped = Math.min(capped, snapshot.limits.toolTimeoutMillis());
        }
        return capped;
    }

    @Override
    public boolean unusableResponsesExhausted(ExecutionSnapshot snapshot, BooleanSupplier repairing) {
        return snapshot.unusableResponses >= 2 || repairing.getAsBoolean();
    }
}
