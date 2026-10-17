package net.velocity.agent;

import net.velocity.agent.state.CallState;
import net.velocity.agent.state.ExecutionSnapshot;
import net.velocity.agent.state.ExecutionStatus;

/**
 * What {@link ConversationRunner} does next at the start of a loop iteration. Constants are declared
 * in evaluation order and {@link #next} returns the first applicable phase, so the order of the
 * checks is the order of this enum and of the conditions in {@code next}.
 */
enum RunPhase {

    /** The execution is no longer running; leave the loop. */
    STOP_NOT_RUNNING,
    /** No active time remains; settle saved calls and stop as partial. */
    STOP_AT_TIME_LIMIT,
    /** An ordinary task reached its total-token ceiling; settle saved calls and stop as partial. */
    STOP_AT_TOKEN_CEILING,
    /** A question is pending; record the wait and leave the loop. */
    WAIT_FOR_QUESTION,
    /** The reporting response was already requested and no retry is permitted; reject its calls and stop. */
    STOP_REPORTING_ATTEMPTED,
    /** Reporting mode with accepted but undispatched calls; reject them, then re-read the state. */
    SETTLE_REPORTING_CALLS,
    /** Accepted or dispatched calls remain unsettled; run the tool executor. */
    PROCESS_ACCEPTED_CALLS,
    /** Source messages arrived while idle; append them to the conversation. */
    ADMIT_SOURCE_MESSAGES,
    /** Reporting mode; wait for in-flight operations to settle before the reporting request. */
    AWAIT_REPORTING_SETTLEMENT,
    /** The cumulative model-request or token budget is exhausted; stop as partial. */
    STOP_MODEL_BUDGET,
    /** Nothing else applies: size the context and request the next model turn. */
    MODEL_TURN;

    /**
     * Pure, ordered decision over the current snapshot. No I/O and no repository access beyond what
     * the lazily supplied {@code facts} perform.
     *
     * <p>
     * Some checks are evaluated against a snapshot that was just refreshed, without
     * re-running the earlier checks. A phase that falls through is therefore followed by a call with
     * {@code from} set to the next constant, which skips every earlier check.
     */
    static RunPhase next(ExecutionSnapshot snapshot, RunFacts facts, RunPhase from) {
        if (from.reaches(STOP_NOT_RUNNING) && snapshot.status != ExecutionStatus.running) {
            return STOP_NOT_RUNNING;
        }
        if (from.reaches(STOP_AT_TIME_LIMIT) && facts.remainingMillis().getAsLong() <= 0) {
            return STOP_AT_TIME_LIMIT;
        }
        if (from.reaches(STOP_AT_TOKEN_CEILING)
                && facts.budget().tokenCeilingReached(snapshot, facts.taskChargedTokens())) {
            return STOP_AT_TOKEN_CEILING;
        }
        if (from.reaches(WAIT_FOR_QUESTION) && !snapshot.pendingQuestions.isEmpty()) {
            return WAIT_FOR_QUESTION;
        }
        if (from.reaches(STOP_REPORTING_ATTEMPTED) && snapshot.partialReporting != null
                && snapshot.partialReporting.attempted()
                && !snapshot.partialReporting.retryAllowed(snapshot.modelTurns, snapshot.unusableResponses)) {
            return STOP_REPORTING_ATTEMPTED;
        }
        if (from.reaches(SETTLE_REPORTING_CALLS) && snapshot.partialReporting != null) {
            return SETTLE_REPORTING_CALLS;
        }
        if (from.reaches(PROCESS_ACCEPTED_CALLS)
                && snapshot.calls.stream().anyMatch(c -> c.state != CallState.settled)) {
            return PROCESS_ACCEPTED_CALLS;
        }
        if (from.reaches(ADMIT_SOURCE_MESSAGES) && !snapshot.pendingSourceMessages.isEmpty()) {
            return ADMIT_SOURCE_MESSAGES;
        }
        if (from.reaches(AWAIT_REPORTING_SETTLEMENT) && snapshot.partialReporting != null) {
            return AWAIT_REPORTING_SETTLEMENT;
        }
        if (from.reaches(STOP_MODEL_BUDGET) && facts.budget().modelBudgetExhausted(snapshot, facts)) {
            return STOP_MODEL_BUDGET;
        }
        return MODEL_TURN;
    }

    /** The phase to resume from after this one completed without ending the iteration. */
    RunPhase following() {
        return values()[ordinal() + 1];
    }

    private boolean reaches(RunPhase phase) {
        return ordinal() <= phase.ordinal();
    }
}
