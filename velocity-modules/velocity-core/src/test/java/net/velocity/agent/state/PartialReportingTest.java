package net.velocity.agent.state;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PartialReportingTest {

    @Test
    void reasonsAreBoundedLowercaseIdentifiersAndTurnsStartAtOne() {
        assertThatThrownBy(() -> PartialReporting.pending("Time Limit", 1))
                .hasMessage("Invalid partial reporting mode");
        assertThatThrownBy(() -> PartialReporting.pending("", 1))
                .hasMessage("Invalid partial reporting mode");
        assertThatThrownBy(() -> PartialReporting.pending("a".repeat(97), 1))
                .hasMessage("Invalid partial reporting mode");
        assertThatThrownBy(() -> PartialReporting.pending("time_limit", 0))
                .hasMessage("Invalid partial reporting mode");
        assertThat(PartialReporting.pending("time_limit", 3).reason()).isEqualTo("time_limit");
    }

    @Test
    void markingAttemptedKeepsReasonAndTurn() {
        var pending = PartialReporting.pending("token_budget", 4);
        assertThat(pending.attempted()).isFalse();
        var attempted = pending.markAttempted();
        assertThat(attempted.attempted()).isTrue();
        assertThat(attempted.reason()).isEqualTo("token_budget");
        assertThat(attempted.startedModelTurn()).isEqualTo(4);
    }

    @Test
    void exactlyOneRetryFollowsAnUnusableCommittedResponse() {
        var attempted = PartialReporting.pending("time_limit", 4).markAttempted();
        assertThat(attempted.retryAllowed(4, 1)).isTrue();
        assertThat(attempted.retryAllowed(4, 0)).isFalse();
        assertThat(attempted.retryAllowed(4, 2)).isFalse();
        assertThat(attempted.retryAllowed(5, 1)).isFalse();
        assertThat(attempted.retried(5, 1)).isTrue();
        assertThat(attempted.retried(4, 1)).isFalse();
        var pending = PartialReporting.pending("time_limit", 4);
        assertThat(pending.retryAllowed(4, 1)).isFalse();
        assertThat(pending.retried(5, 1)).isFalse();
    }
}
