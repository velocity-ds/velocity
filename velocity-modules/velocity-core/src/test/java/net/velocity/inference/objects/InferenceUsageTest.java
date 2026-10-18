package net.velocity.inference.objects;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.velocity.inference.objects.InferenceResponse.Usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InferenceUsageTest {

    @Test
    void consistentTotalsAreComplete() {
        var usage = new Usage(100, 50);
        assertThat(usage.totalTokens()).isEqualTo(150);
        assertThat(usage.incomplete()).isFalse();
        assertThat(usage.unsupportedCategories()).isEmpty();
    }

    @Test
    void inconsistentProviderTotalsArePreservedAsIncompleteEvidence() {
        assertThat(new Usage(100, 50, 200).incomplete()).isTrue();
        assertThat(new Usage(100, 50, 150, 101, 0, 0, 0, 0, false, null).incomplete()).isTrue();
        assertThat(new Usage(100, 50, 150, 40, 70, 0, 0, 0, false, null).incomplete()).isTrue();
        assertThat(new Usage(100, 50, 150, 0, 20, 15, 10, 0, false, null).incomplete()).isTrue();
        assertThat(new Usage(100, 50, 150, 0, 0, 0, 0, 51, false, null).incomplete()).isTrue();
        assertThat(new Usage(100, 50, 150, 40, 60, 30, 30, 50, false, null).incomplete()).isFalse();
    }

    @Test
    void negativeCountsAreRejected() {
        assertThatThrownBy(() -> new Usage(-1, 0)).hasMessage("Token usage must not be negative");
        assertThatThrownBy(() -> new Usage(1, 1, 2, 0, 0, 0, 0, -1, false, List.of()))
                .hasMessage("Token usage must not be negative");
    }

    @Test
    void withIncompleteOnlyEverAddsTheFlag() {
        var usage = new Usage(100, 50);
        assertThat(usage.withIncomplete(false).incomplete()).isFalse();
        assertThat(usage.withIncomplete(true).incomplete()).isTrue();
        assertThat(usage.withIncomplete(true).withIncomplete(false).incomplete()).isTrue();
        assertThat(new Usage(1, 1, 2, 0, 0, 0, 0, 0, false, List.of("audio")).unsupportedCategories())
                .containsExactly("audio");
    }
}
