package net.velocity.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.velocity.agent.state.ExecutionLimits;
import net.velocity.agent.state.ExecutionSnapshot;
import net.velocity.agent.state.MonitoringExecution;

import static org.assertj.core.api.Assertions.assertThat;

class CumulativeLimitsPolicyTest {

    private final CumulativeLimitsPolicy policy = CumulativeLimitsPolicy.INSTANCE;
    private final ExecutionSnapshot snapshot = new ExecutionSnapshot();
    /** Root accounting only: no repository is touched by the methods exercised here. */
    private final ExecutionProgress progress = new ExecutionProgress(null, () -> 1_024);

    @BeforeEach
    void limits() {
        snapshot.limits = new ExecutionLimits(10_000, 60_000, 5, 3, 30_000, 1_000, 512L);
    }

    @Test
    void theTaskTokenCeilingNeverAppliesToCumulativeExecutions() {
        assertThat(policy.tokenCeilingReached(snapshot, () -> Long.MAX_VALUE)).isFalse();
    }

    @Test
    void remainingTokensNeverGoNegative() {
        assertThat(policy.remainingTokens(snapshot, () -> 4_000)).isEqualTo(6_000);
        assertThat(policy.remainingTokens(snapshot, () -> 12_000)).isZero();
    }

    @Test
    void outputReserveCapsTheProviderMaximumOnlyWhenSet() {
        assertThat(policy.outputAllowance(snapshot, 4_096)).isEqualTo(512);
        assertThat(policy.outputAllowance(snapshot, 256)).isEqualTo(256);
        snapshot.limits = new ExecutionLimits(10_000, 60_000, 5, 3, 30_000, 1_000, 0L);
        assertThat(policy.outputAllowance(snapshot, 4_096)).isEqualTo(4_096);
    }

    @Test
    void modelBudgetIsExhaustedByRequestsOrTokens() {
        assertThat(policy.modelBudgetExhausted(snapshot, facts(5, 0))).isTrue();
        assertThat(policy.modelBudgetExhausted(snapshot, facts(4, 10_000))).isTrue();
        assertThat(policy.modelBudgetExhausted(snapshot, facts(4, 9_999))).isFalse();
    }

    @Test
    void finalReportIsRequiredNearEveryCumulativeLimit() {
        assertThat(policy.finalReportRequired(snapshot, progress)).isFalse();
        snapshot.toolCalls = 3;
        assertThat(policy.finalReportRequired(snapshot, progress)).isTrue();
        snapshot.toolCalls = 0;
        snapshot.modelTurns = 4;
        assertThat(policy.finalReportRequired(snapshot, progress)).isTrue();
        snapshot.modelTurns = 0;
        snapshot.totalTokens = 7_952;
        assertThat(policy.finalReportRequired(snapshot, progress)).isTrue();
        snapshot.totalTokens = 7_951;
        assertThat(policy.finalReportRequired(snapshot, progress)).isFalse();
    }

    @Test
    void finalReportReserveCoversThisRequestAndOneMore() {
        // Request reserve is 2000 + 1024 = 3024; a report needs the same again.
        snapshot.totalTokens = 1_000;
        assertThat(policy.finalReportReserveRequired(snapshot, progress, 2_000)).isFalse();
        snapshot.totalTokens = 4_000;
        assertThat(policy.finalReportReserveRequired(snapshot, progress, 2_000)).isTrue();
    }

    @Test
    void tokenExhaustionUsesChargedAndRecordedTotals() {
        assertThat(policy.tokensExhausted(snapshot, () -> 9_999)).isFalse();
        assertThat(policy.tokensExhausted(snapshot, () -> 10_000)).isTrue();
        snapshot.totalTokens = 10_000;
        assertThat(policy.recordedTokensExhausted(snapshot)).isTrue();
        snapshot.totalTokens = 9_999;
        assertThat(policy.recordedTokensExhausted(snapshot)).isFalse();
    }

    @Test
    void everyRunDeductsElapsedTime() {
        assertThat(policy.deductsElapsedTime(true)).isTrue();
        assertThat(policy.deductsElapsedTime(false)).isTrue();
    }

    @Test
    void toolCallsAreCountedAgainstTheLimitBeforeDispatch() {
        assertThat(policy.toolCallsExceeded(snapshot, 2, 1)).isFalse();
        assertThat(policy.toolCallsExceeded(snapshot, 2, 2)).isTrue();
    }

    @Test
    void toolTimeoutsAreCappedByTheDefinitionAndByMonitoringLimits() {
        assertThat(policy.capToolTimeout(snapshot, 60_000, 50_000)).isEqualTo(50_000);
        assertThat(policy.capToolTimeout(snapshot, 20_000, 50_000)).isEqualTo(20_000);
        assertThat(policy.capToolTimeout(snapshot, 0, 50_000)).isEqualTo(30_000);
        snapshot.monitoring = new MonitoringExecution("job1", "sched1", "occ1", 1);
        assertThat(policy.capToolTimeout(snapshot, 60_000, 50_000)).isEqualTo(30_000);
    }

    @Test
    void unusableResponsesStopAfterTwoOrWhileRepairing() {
        assertThat(policy.unusableResponsesExhausted(snapshot, () -> false)).isFalse();
        assertThat(policy.unusableResponsesExhausted(snapshot, () -> true)).isTrue();
        snapshot.unusableResponses = 2;
        assertThat(policy.unusableResponsesExhausted(snapshot, () -> false)).isTrue();
    }

    private RunFacts facts(long modelRequests, long charged) {
        return new RunFacts(policy, () -> 1_000, () -> modelRequests, () -> charged, () -> charged);
    }
}
