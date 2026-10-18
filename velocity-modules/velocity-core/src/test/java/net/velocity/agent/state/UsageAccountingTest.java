package net.velocity.agent.state;

import org.junit.jupiter.api.Test;

import net.velocity.inference.objects.InferenceResponse.Usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UsageAccountingTest {

    private final ConversationState state = new ConversationState();

    @Test
    void cumulativeReportsAreChargedAsDeltasPerSource() {
        UsageAccounting.observe(state, "model:1", new Usage(100, 50), false);
        assertThat(state.inputTokens).isEqualTo(100);
        assertThat(state.outputTokens).isEqualTo(50);
        assertThat(state.totalTokens).isEqualTo(150);
        assertThat(state.usageMissing).isFalse();

        UsageAccounting.observe(state, "model:1", new Usage(120, 60), false);
        assertThat(state.totalTokens).isEqualTo(180);

        UsageAccounting.observe(state, "model:2", new Usage(10, 5), false);
        assertThat(state.totalTokens).isEqualTo(195);
        assertThat(state.usageSources).containsOnlyKeys("model:1", "model:2");
    }

    @Test
    void regressingReportsNeverReduceTotalsOrClearTheMissingFlag() {
        UsageAccounting.observe(state, "model:1", new Usage(100, 50), false);
        UsageAccounting.observe(state, "model:1", new Usage(90, 40), false);
        assertThat(state.totalTokens).isEqualTo(150);
        assertThat(state.usageMissing).isFalse();

        UsageAccounting.observe(state, "model:1", null, true);
        assertThat(state.totalTokens).isEqualTo(150);
        assertThat(state.usageMissing).isTrue();
        // A later regressing report cannot clear the recorded gap.
        UsageAccounting.observe(state, "model:1", new Usage(90, 40), false);
        assertThat(state.usageMissing).isTrue();
        // A non-regressing complete report does.
        UsageAccounting.observe(state, "model:1", new Usage(100, 50), false);
        assertThat(state.usageMissing).isFalse();
    }

    @Test
    void incompleteProviderUsageMarksTheExecution() {
        UsageAccounting.observe(state, "model:1", new Usage(100, 50, 200), false);
        assertThat(state.totalTokens).isEqualTo(200);
        assertThat(state.usageMissing).isTrue();
    }

    @Test
    void modelTurnsWithoutATrackedSourceAreMissing() {
        state.modelTurns = 2;
        UsageAccounting.observe(state, "model:1", new Usage(100, 50), false);
        assertThat(state.usageMissing).isTrue();
        UsageAccounting.observe(state, "model:2", new Usage(1, 1), false);
        assertThat(state.usageMissing).isFalse();
    }

    @Test
    void legacyTotalsWithoutSourcesStayExplicitlyMissing() {
        state.totalTokens = 42;
        UsageAccounting.refreshMissing(state);
        assertThat(state.usageMissing).isTrue();
        state.totalTokens = 0;
        UsageAccounting.refreshMissing(state);
        assertThat(state.usageMissing).isFalse();
    }

    @Test
    void sourcesAreRequired() {
        assertThatThrownBy(() -> UsageAccounting.observe(state, " ", new Usage(1, 1), false))
                .hasMessage("Usage source is required");
    }
}
