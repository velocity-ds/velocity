package net.velocity.inference;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InferenceDeadlineScopeTest {

    @Test
    void outsideAScopeThereIsNoDeadline() {
        assertThat(InferenceDeadlineScope.remaining()).isEmpty();
        assertThat(InferenceDeadlineScope.remainingOr(Duration.ofSeconds(3)))
                .isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void nestedScopesKeepTheEarliestDeadlineAndRestoreOnClose() {
        try (var outer = InferenceDeadlineScope.open(InferenceDeadlineScope.captureDeadline(10_000))) {
            assertThat(InferenceDeadlineScope.remaining().orElseThrow())
                    .isPositive()
                    .isLessThanOrEqualTo(Duration.ofSeconds(10))
                    .isGreaterThan(Duration.ofSeconds(5));
            assertThat(InferenceDeadlineScope.remainingOr(Duration.ofHours(1)))
                    .isLessThanOrEqualTo(Duration.ofSeconds(10));

            try (var inner = InferenceDeadlineScope.open(InferenceDeadlineScope.captureDeadline(1_000))) {
                assertThat(InferenceDeadlineScope.remaining().orElseThrow())
                        .isLessThanOrEqualTo(Duration.ofSeconds(1));
            }
            assertThat(InferenceDeadlineScope.remaining().orElseThrow()).isGreaterThan(Duration.ofSeconds(5));

            // A later deadline captured inside the scope collapses to the enclosing one.
            try (var later = InferenceDeadlineScope.open(InferenceDeadlineScope.captureDeadline(60_000))) {
                assertThat(InferenceDeadlineScope.remaining().orElseThrow())
                        .isLessThanOrEqualTo(Duration.ofSeconds(10));
            }
        }
        assertThat(InferenceDeadlineScope.remaining()).isEmpty();
    }

    @Test
    void anElapsedDeadlineStillYieldsAPositiveMillisecond() {
        try (var elapsed = InferenceDeadlineScope.open(System.nanoTime() - 1_000_000)) {
            assertThat(InferenceDeadlineScope.remaining().orElseThrow()).isEqualTo(Duration.ofMillis(1));
        }
    }
}
