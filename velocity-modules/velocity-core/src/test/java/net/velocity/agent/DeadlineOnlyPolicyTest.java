package net.velocity.agent;

import org.junit.jupiter.api.Test;

import net.velocity.agent.state.ExecutionSnapshot;

import static org.assertj.core.api.Assertions.assertThat;

class DeadlineOnlyPolicyTest {

    private final ExecutionSnapshot snapshot = new ExecutionSnapshot();

    @Test
    void disabledCeilingNeverStopsAndNeverReadsTheCharge() {
        var policy = new DeadlineOnlyPolicy(0);
        assertThat(policy.tokenCeilingReached(snapshot, () -> {
            throw new AssertionError("charge must not be read when the ceiling is disabled");
        })).isFalse();
    }

    @Test
    void ceilingStopsOnceTheTaskChargeReachesIt() {
        var policy = new DeadlineOnlyPolicy(100);
        assertThat(policy.tokenCeilingReached(snapshot, () -> 99)).isFalse();
        assertThat(policy.tokenCeilingReached(snapshot, () -> 100)).isTrue();
        assertThat(policy.tokenCeilingReached(snapshot, () -> 5_000)).isTrue();
    }

    @Test
    void cumulativeCountersAreAccountingOnly() {
        var policy = new DeadlineOnlyPolicy(100);
        var facts = new RunFacts(policy, () -> 1, () -> Integer.MAX_VALUE, () -> Long.MAX_VALUE, () -> 0);
        assertThat(policy.remainingTokens(snapshot, () -> Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE);
        assertThat(policy.outputAllowance(snapshot, 4_096)).isEqualTo(4_096);
        assertThat(policy.modelBudgetExhausted(snapshot, facts)).isFalse();
        assertThat(policy.finalReportRequired(snapshot, null)).isFalse();
        assertThat(policy.finalReportReserveRequired(snapshot, null, Long.MAX_VALUE)).isFalse();
        assertThat(policy.tokensExhausted(snapshot, () -> Long.MAX_VALUE)).isFalse();
        assertThat(policy.recordedTokensExhausted(snapshot)).isFalse();
        assertThat(policy.toolCallsExceeded(snapshot, Integer.MAX_VALUE, 1)).isFalse();
        assertThat(policy.capToolTimeout(snapshot, 1, 99_999)).isEqualTo(99_999);
        assertThat(policy.unusableResponsesExhausted(snapshot, () -> true)).isFalse();
    }

    @Test
    void onlyRootRunsDeductElapsedTime() {
        var policy = new DeadlineOnlyPolicy(0);
        assertThat(policy.deductsElapsedTime(true)).isTrue();
        assertThat(policy.deductsElapsedTime(false)).isFalse();
    }
}
