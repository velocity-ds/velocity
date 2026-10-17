package net.velocity.agent;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import net.velocity.agent.state.ExecutionSnapshot;

/**
 * The budget regime of one execution, chosen from the snapshot a run sees. Ordinary user tasks are
 * bounded by their active-time deadline and the optional task token ceiling
 * ({@link DeadlineOnlyPolicy}); monitoring executions carry cumulative model-request,
 * tool-call and token limits ({@link CumulativeLimitsPolicy}). A child run sees a view that copies the
 * root's purpose but not its monitoring association, so a child is classified by purpose alone. Every
 * purpose today is {@code generic}, so every child uses {@link DeadlineOnlyPolicy}. Monitoring roots cannot
 * delegate, so they have no children.
 * The run loop and the tool executor ask the policy instead of branching on the task purpose.
 */
interface BudgetPolicy {

    /**
     * Whether the task-wide token ceiling for ordinary tasks was reached. Monitoring and learning
     * executions keep their own budgets and never reach it. {@code taskCharged} is read lazily, and not
     * at all when the ceiling is disabled.
     */
    boolean tokenCeilingReached(ExecutionSnapshot snapshot, LongSupplier taskCharged);

    /** Tokens still available to the next request; unbounded when no token limit applies. */
    long remainingTokens(ExecutionSnapshot snapshot, LongSupplier charged);

    /**
     * The output tokens one model request may generate and reserves, given the pinned binding's maximum.
     * Cumulative limits may lower it so the reporting reserve matches what a turn can actually emit.
     */
    long outputAllowance(ExecutionSnapshot snapshot, long maxOutputTokens);

    /** Whether the model-request or token budget forbids starting another model turn. */
    boolean modelBudgetExhausted(ExecutionSnapshot snapshot, RunFacts facts);

    /** Whether the next model turn must be the tools-disabled final report. */
    boolean finalReportRequired(ExecutionSnapshot snapshot, ExecutionProgress progress);

    /** Whether the remaining budget cannot cover this request plus a final report. */
    boolean finalReportReserveRequired(
            ExecutionSnapshot snapshot,
            ExecutionProgress progress,
            long estimatedInputTokens);

    /** Whether the charged tokens, including reservations, reached the token limit. */
    boolean tokensExhausted(ExecutionSnapshot snapshot, LongSupplier charged);

    /** Whether the tokens recorded so far reached the token limit. */
    boolean recordedTokensExhausted(ExecutionSnapshot snapshot);

    /** Whether time already spent in the current run is deducted from the remaining time. */
    boolean deductsElapsedTime(boolean rootRun);

    /** Whether dispatching {@code requested} more tool calls would exceed the tool-call limit. */
    boolean toolCallsExceeded(ExecutionSnapshot snapshot, int dispatches, int requested);

    /** The tool invocation timeout after applying any per-tool limit. */
    long capToolTimeout(ExecutionSnapshot snapshot, long definitionTimeoutMillis, long timeout);

    /** Whether the unusable-response limit was reached. */
    boolean unusableResponsesExhausted(ExecutionSnapshot snapshot, BooleanSupplier repairing);
}
