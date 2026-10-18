package net.velocity.agent;

import org.junit.jupiter.api.Test;

import net.velocity.agent.state.AcceptedCall;
import net.velocity.agent.state.CallState;
import net.velocity.agent.state.ExecutionLimits;
import net.velocity.agent.state.ExecutionSnapshot;
import net.velocity.agent.state.ExecutionStatus;
import net.velocity.agent.state.PartialReporting;
import net.velocity.agent.state.Question;
import net.velocity.agent.state.SourceMessage;
import net.velocity.agent.state.SourceTurn;

import static org.assertj.core.api.Assertions.assertThat;

class RunPhaseTest {

    private final ExecutionSnapshot snapshot = new ExecutionSnapshot();

    @Test
    void anIdleRunningExecutionTakesAModelTurn() {
        assertThat(next(deadlineOnly(0), 1_000)).isEqualTo(RunPhase.MODEL_TURN);
    }

    @Test
    void stoppedExecutionsLeaveTheLoopFirst() {
        snapshot.status = ExecutionStatus.cancelled;
        snapshot.pendingQuestions.add(new Question("q1", "Which one?"));
        assertThat(next(deadlineOnly(0), 0)).isEqualTo(RunPhase.STOP_NOT_RUNNING);
    }

    @Test
    void timeLimitPrecedesTheTokenCeiling() {
        assertThat(next(deadlineOnly(10), 0)).isEqualTo(RunPhase.STOP_AT_TIME_LIMIT);
        assertThat(next(deadlineOnly(10), 1_000)).isEqualTo(RunPhase.STOP_AT_TOKEN_CEILING);
    }

    @Test
    void pendingQuestionsWaitBeforeAnyReportingOrToolWork() {
        snapshot.pendingQuestions.add(new Question("q1", "Which one?"));
        snapshot.partialReporting = PartialReporting.pending("time_limit", 1);
        snapshot.calls.add(call("c1"));
        assertThat(next(deadlineOnly(0), 1_000)).isEqualTo(RunPhase.WAIT_FOR_QUESTION);
    }

    @Test
    void anAttemptedReportWithoutARetryStops() {
        snapshot.partialReporting = PartialReporting.pending("time_limit", 1).markAttempted();
        snapshot.modelTurns = 1;
        assertThat(next(deadlineOnly(0), 1_000)).isEqualTo(RunPhase.STOP_REPORTING_ATTEMPTED);
        // One unusable committed response permits exactly one more reporting request.
        snapshot.unusableResponses = 1;
        assertThat(next(deadlineOnly(0), 1_000)).isEqualTo(RunPhase.SETTLE_REPORTING_CALLS);
    }

    @Test
    void reportingModeSettlesCallsThenAwaitsSettlementBeforeItsRequest() {
        snapshot.partialReporting = PartialReporting.pending("time_limit", 1);
        assertThat(next(deadlineOnly(0), 1_000)).isEqualTo(RunPhase.SETTLE_REPORTING_CALLS);
        assertThat(
                RunPhase.next(
                        snapshot,
                        facts(deadlineOnly(0), 1_000),
                        RunPhase.SETTLE_REPORTING_CALLS.following()))
                .isEqualTo(RunPhase.AWAIT_REPORTING_SETTLEMENT);
    }

    @Test
    void unsettledCallsRunTheToolExecutorBeforeNewSourceMessages() {
        snapshot.calls.add(call("c1"));
        snapshot.pendingSourceMessages.add(
                new SourceMessage(new SourceTurn("W1", "C1", "T1", "M1"), "U1", null, "more"));
        assertThat(next(deadlineOnly(0), 1_000)).isEqualTo(RunPhase.PROCESS_ACCEPTED_CALLS);
        snapshot.calls.getFirst().state = CallState.settled;
        assertThat(next(deadlineOnly(0), 1_000)).isEqualTo(RunPhase.ADMIT_SOURCE_MESSAGES);
    }

    @Test
    void anExhaustedCumulativeBudgetStopsInsteadOfRequestingAModelTurn() {
        snapshot.limits = new ExecutionLimits(10_000, 60_000, 5, 3, 30_000, 1_000, 0L);
        var exhausted = new RunFacts(CumulativeLimitsPolicy.INSTANCE, () -> 1_000, () -> 5, () -> 0, () -> 0);
        assertThat(RunPhase.next(snapshot, exhausted, RunPhase.STOP_NOT_RUNNING))
                .isEqualTo(RunPhase.STOP_MODEL_BUDGET);
        var available = new RunFacts(CumulativeLimitsPolicy.INSTANCE, () -> 1_000, () -> 4, () -> 0, () -> 0);
        assertThat(RunPhase.next(snapshot, available, RunPhase.STOP_NOT_RUNNING))
                .isEqualTo(RunPhase.MODEL_TURN);
    }

    @Test
    void resumingFromALaterPhaseSkipsEarlierChecks() {
        snapshot.status = ExecutionStatus.failed;
        assertThat(RunPhase.next(snapshot, facts(deadlineOnly(0), 0), RunPhase.STOP_AT_TOKEN_CEILING))
                .isEqualTo(RunPhase.MODEL_TURN);
        assertThat(RunPhase.STOP_NOT_RUNNING.following()).isEqualTo(RunPhase.STOP_AT_TIME_LIMIT);
        assertThat(RunPhase.STOP_MODEL_BUDGET.following()).isEqualTo(RunPhase.MODEL_TURN);
    }

    private RunPhase next(BudgetPolicy budget, long remainingMillis) {
        return RunPhase.next(snapshot, facts(budget, remainingMillis), RunPhase.STOP_NOT_RUNNING);
    }

    private static RunFacts facts(BudgetPolicy budget, long remainingMillis) {
        return new RunFacts(budget, () -> remainingMillis, () -> 0, () -> 0, () -> 1_000);
    }

    private static BudgetPolicy deadlineOnly(long ceiling) {
        return new DeadlineOnlyPolicy(ceiling);
    }

    private static AcceptedCall call(String id) {
        var call = new AcceptedCall();
        call.id = id;
        call.name = "echo";
        return call;
    }
}
