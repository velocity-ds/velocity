package net.velocity.agent.state;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionLimitsTest {

    private static final ExecutionLimits SERVER = new ExecutionLimits(
            10_000,
            60_000,
            10,
            20,
            30_000,
            4_096,
            0L);

    @Test
    void defaultsArePositiveWithNoOutputReserve() {
        var defaults = ExecutionLimits.defaults();
        assertThat(defaults.totalTokens()).isEqualTo(470_000);
        assertThat(defaults.activeMillis()).isEqualTo(30 * 60_000L);
        assertThat(defaults.outputReserve()).isZero();
    }

    @Test
    void everyLimitMustBePositiveAndTheReserveNonNegative() {
        assertThatThrownBy(() -> new ExecutionLimits(0, 1, 1, 1, 1, 1, 0L))
                .hasMessage("Execution limits must be positive");
        assertThatThrownBy(() -> new ExecutionLimits(1, 1, 0, 1, 1, 1, 0L))
                .hasMessage("Execution limits must be positive");
        assertThatThrownBy(() -> new ExecutionLimits(1, 1, 1, 1, 1, 1, -1L))
                .hasMessage("Output reserve must not be negative");
        assertThat(new ExecutionLimits(1, 1, 1, 1, 1, 1, null).outputReserve()).isZero();
    }

    @Test
    void taskTimeOnlyKeepsTheDeadlineAndRemovesEveryCounterCutoff() {
        var limits = new ExecutionLimits(1_000, 5_000, 2, 3, 400, 50, 7L).taskTimeOnly();
        assertThat(limits.totalTokens()).isEqualTo(Long.MAX_VALUE);
        assertThat(limits.modelTurns()).isEqualTo(Integer.MAX_VALUE);
        assertThat(limits.toolCalls()).isEqualTo(Integer.MAX_VALUE);
        assertThat(limits.activeMillis()).isEqualTo(5_000);
        assertThat(limits.toolTimeoutMillis()).isEqualTo(5_000);
        assertThat(limits.outputPreviewChars()).isEqualTo(50);
        assertThat(limits.outputReserve()).isEqualTo(7);
    }

    @Test
    void requestedLimitsMayOnlyReduceServerLimits() {
        var smaller = new ExecutionLimits(5_000, 30_000, 5, 10, 15_000, 2_048, 0L);
        assertThatCode(() -> smaller.validateWithin(SERVER)).doesNotThrowAnyException();
        assertThat(SERVER.reduce(smaller)).isSameAs(smaller);
        assertThat(SERVER.reduce(null)).isSameAs(SERVER);
        assertThatThrownBy(
                () -> new ExecutionLimits(10_001, 60_000, 10, 20, 30_000, 4_096, 0L).validateWithin(SERVER))
                .hasMessage("Execution limits must not exceed server limits");
        assertThatThrownBy(
                () -> new ExecutionLimits(10_000, 60_000, 10, 21, 30_000, 4_096, 0L).validateWithin(SERVER))
                .hasMessage("Execution limits must not exceed server limits");
        assertThatThrownBy(() -> smaller.validateWithin(null))
                .hasMessage("Execution limits must not exceed server limits");
    }

    @Test
    void aZeroReserveMeansTheProfileMaximumSoOnlyAPositiveServerReserveBinds() {
        var anyReserve = new ExecutionLimits(1_000, 1_000, 1, 1, 1_000, 100, 0L);
        assertThatCode(() -> anyReserve.validateWithin(SERVER)).doesNotThrowAnyException();
        assertThatCode(
                () -> new ExecutionLimits(1_000, 1_000, 1, 1, 1_000, 100, 99_999L).validateWithin(SERVER))
                .doesNotThrowAnyException();

        var bounded = new ExecutionLimits(10_000, 60_000, 10, 20, 30_000, 4_096, 1_000L);
        assertThatThrownBy(() -> anyReserve.validateWithin(bounded))
                .hasMessage("Execution limits must not exceed server limits");
        assertThatThrownBy(
                () -> new ExecutionLimits(1_000, 1_000, 1, 1, 1_000, 100, 1_001L).validateWithin(bounded))
                .hasMessage("Execution limits must not exceed server limits");
        assertThatCode(
                () -> new ExecutionLimits(1_000, 1_000, 1, 1, 1_000, 100, 1_000L).validateWithin(bounded))
                .doesNotThrowAnyException();
    }
}
