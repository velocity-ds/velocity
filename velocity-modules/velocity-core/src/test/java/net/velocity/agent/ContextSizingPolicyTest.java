package net.velocity.agent;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import org.junit.jupiter.api.Test;

import net.velocity.agent.state.AcceptedCall;
import net.velocity.agent.state.CallState;
import net.velocity.agent.state.ExecutionSnapshot;
import net.velocity.inference.objects.InferenceContent;
import net.velocity.inference.objects.InferenceMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContextSizingPolicyTest {

    @Test
    void allowanceIsBoundedByInputAllowanceAndRemainingBudgetMinusOutputReserve() {
        assertThat(ContextSizingPolicy.allowance(10_000, 8_000, 4_000)).isEqualTo(6_000);
        assertThat(ContextSizingPolicy.allowance(100_000, 8_000, 4_000)).isEqualTo(8_000);
        assertThat(ContextSizingPolicy.allowance(4_000, 8_000, 4_000)).isZero();
        assertThat(ContextSizingPolicy.allowance(-5, 8_000, 4_000)).isZero();
        assertThatThrownBy(() -> ContextSizingPolicy.allowance(10, 0, 4))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContextSizingPolicy.allowance(10, 4, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reserveSaturatesInsteadOfOverflowing() {
        assertThat(ContextSizingPolicy.reserve(100, 50)).isEqualTo(150);
        assertThat(ContextSizingPolicy.reserve(Long.MAX_VALUE - 1, 10)).isEqualTo(Long.MAX_VALUE);
        assertThatThrownBy(() -> ContextSizingPolicy.reserve(-1, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContextSizingPolicy.reserve(1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decisionsSeparateWindowOverflowFromBudgetExhaustion() {
        var estimate = new ContextSizingPolicy.Estimate(10, 10, 10, 10, 5_000, ContextSizingPolicy.ESTIMATOR);
        assertThat(ContextSizingPolicy.decide(estimate, false, 100_000, 8_000, 1_000))
                .isEqualTo(ContextSizingPolicy.Decision.invalid_context);
        assertThat(ContextSizingPolicy.decide(null, true, 100_000, 8_000, 1_000))
                .isEqualTo(ContextSizingPolicy.Decision.invalid_context);
        assertThat(ContextSizingPolicy.decide(estimate, true, 100_000, 4_000, 1_000))
                .isEqualTo(ContextSizingPolicy.Decision.input_allowance);
        assertThat(ContextSizingPolicy.decide(estimate, true, 100_000, 8_000, 1_000))
                .isEqualTo(ContextSizingPolicy.Decision.accepted);
        assertThat(ContextSizingPolicy.decide(estimate, true, 5_500, 8_000, 1_000))
                .isEqualTo(ContextSizingPolicy.Decision.remaining_budget);
    }

    @Test
    void providerTokenCountsReplaceTheByteEstimateButKeepByteDiagnostics() {
        var estimate = new ContextSizingPolicy.Estimate(1, 2, 3, 4, 5_000, ContextSizingPolicy.ESTIMATOR);
        assertThat(estimate.withProviderInputTokens(OptionalLong.empty())).isSameAs(estimate);
        var counted = estimate.withProviderInputTokens(OptionalLong.of(100));
        assertThat(counted.estimatedInputTokens()).isEqualTo(100 + ContextSizingPolicy.OVERHEAD_TOKENS);
        assertThat(counted.estimatorKind()).isEqualTo(ContextSizingPolicy.PROVIDER_ESTIMATOR);
        assertThat(counted.messageBytes()).isEqualTo(1);
        assertThat(counted.envelopeBytes()).isEqualTo(4);
        assertThatThrownBy(() -> estimate.withProviderInputTokens(OptionalLong.of(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void byteEstimateAccountsForEveryPartPlusOverhead() {
        var definition = new ToolRegistry.Definition(
                "echo",
                "Echo text",
                Map.of("type", "object"),
                Map.of("type", "object"),
                "system.read",
                true,
                false,
                false,
                1_000,
                ToolRegistry.MonitoringAccess.observation,
                (context, input) -> input);
        var estimate = ContextSizingPolicy.estimate(
                List.of(InferenceMessage.user("hello")),
                "Be brief.",
                List.of(definition));
        assertThat(estimate.estimatorKind()).isEqualTo(ContextSizingPolicy.ESTIMATOR);
        assertThat(estimate.messageBytes()).isPositive();
        assertThat(estimate.schemaBytes()).isPositive();
        assertThat(estimate.instructionBytes()).isPositive();
        assertThat(estimate.envelopeBytes()).isPositive();
        assertThat(estimate.estimatedInputTokens()).isEqualTo(
                estimate.messageBytes() + estimate.schemaBytes() + estimate.instructionBytes()
                        + estimate.envelopeBytes() + ContextSizingPolicy.OVERHEAD_TOKENS);
        assertThat(
                ContextSizingPolicy.estimate(List.of(InferenceMessage.user("hello")), null, List.of())
                        .instructionBytes())
                .isEqualTo(2);
    }

    @Test
    void diagnosticsCarryOnlyNumbersAndIdentifiers() {
        var estimate = new ContextSizingPolicy.Estimate(1, 2, 3, 4, 5_000, ContextSizingPolicy.ESTIMATOR);
        var diagnostics = estimate.diagnostics(-10, 8_000, 1_000, "default", "claude", "model");
        assertThat(diagnostics)
                .containsEntry("estimated_input_tokens", 5_000L)
                .containsEntry("remaining_cumulative_tokens", 0L)
                .containsEntry("effective_input_allowance", 0L)
                .containsEntry("profile_id", "default")
                .containsEntry("model", "model");
    }

    @Test
    void completionMessagesAddPlaceholderResultsOnlyForUnsettledPendingCalls() {
        var snapshot = new ExecutionSnapshot();
        var pending = new AcceptedCall();
        pending.id = "c1";
        pending.name = "echo";
        var settled = new AcceptedCall();
        settled.id = "c2";
        settled.name = "echo";
        settled.state = CallState.settled;
        snapshot.calls.add(pending);
        snapshot.calls.add(settled);
        var messages = List.of(
                new InferenceMessage(
                        InferenceMessage.Role.ASSISTANT,
                        List.of(
                                new InferenceContent.ToolCall("c1", "echo", Map.of("text", "hi"), null),
                                new InferenceContent.ToolCall("c2", "echo", Map.of("text", "ho"), null))));

        var completed = ContextSizingPolicy.completionMessages(snapshot, messages);

        assertThat(completed).hasSize(2);
        assertThat(completed.get(1).role()).isEqualTo(InferenceMessage.Role.USER);
        assertThat(completed.get(1).content()).singleElement()
                .isInstanceOfSatisfying(
                        InferenceContent.ToolResult.class,
                        result -> assertThat(result.toolUseId()).isEqualTo("c1"));
    }

    @Test
    void completionMessagesLeaveAnsweredConversationsUnchanged() {
        var snapshot = new ExecutionSnapshot();
        var call = new AcceptedCall();
        call.id = "c1";
        call.name = "echo";
        snapshot.calls.add(call);
        var messages = List.of(
                new InferenceMessage(
                        InferenceMessage.Role.ASSISTANT,
                        List.of(new InferenceContent.ToolCall("c1", "echo", Map.of(), null))),
                new InferenceMessage(
                        InferenceMessage.Role.USER,
                        List.of(
                                new InferenceContent.ToolResult(
                                        "c1",
                                        List.of(new InferenceContent.Text("done")),
                                        false,
                                        null))));
        assertThat(ContextSizingPolicy.completionMessages(snapshot, messages)).isSameAs(messages);
    }
}
