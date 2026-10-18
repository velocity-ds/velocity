package net.velocity.inference;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InferenceCapacityTest {

    @Test
    void undeclaredCapacityAlwaysFits() {
        assertThat(InferenceCapacity.fits(0, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE)).isTrue();
    }

    @Test
    void inputOutputAndMarginMustFitTheDeclaredCapacity() {
        assertThat(InferenceCapacity.fits(100, 50, 30, 20)).isTrue();
        assertThat(InferenceCapacity.fits(100, 50, 30, 21)).isFalse();
    }

    @Test
    void overflowCountsAsNotFitting() {
        assertThat(InferenceCapacity.fits(Long.MAX_VALUE, Long.MAX_VALUE, 1, 0)).isFalse();
        assertThat(InferenceCapacity.fits(Long.MAX_VALUE, Long.MAX_VALUE - 1, 1, 0)).isTrue();
    }
}
