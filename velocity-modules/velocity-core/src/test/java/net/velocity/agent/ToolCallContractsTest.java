package net.velocity.agent;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.velocity.agent.state.AcceptedCall;
import net.velocity.agent.state.TrustedScope;
import net.velocity.inference.objects.InferenceContent;
import net.velocity.inference.objects.InferenceToolCalls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolCallContractsTest {

    private static final TrustedScope SCOPE = new TrustedScope(
            "W1",
            "U1",
            "C1",
            "B1",
            "slack",
            "7b6a4a2e-1b2c-4d3e-8f90-0123456789ab");

    private static final ToolRegistry.Definition ECHO = new ToolRegistry.Definition(
            "echo",
            "Echo text",
            Map.of(
                    "type",
                    "object",
                    "properties",
                    Map.of("text", Map.of("type", "string", "maxLength", 5)),
                    "required",
                    List.of("text"),
                    "additionalProperties",
                    false),
            Map.of("type", "object"),
            "system.read",
            true,
            false,
            false,
            1_000,
            ToolRegistry.MonitoringAccess.observation,
            (context, input) -> input);

    @Test
    void acceptedCallsCarryTheDecodedCallAndItsScopedBinding() {
        var decoded = new InferenceToolCalls.Call("c1", "echo", Map.of("text", "hi"), null, null);
        AcceptedCall accepted = ToolCallContracts.accept(decoded, SCOPE, "binding-1");
        assertThat(accepted.id).isEqualTo("c1");
        assertThat(accepted.name).isEqualTo("echo");
        assertThat(accepted.input).containsExactly(Map.entry("text", "hi"));
        assertThat(accepted.inputError).isNull();
        assertThat(accepted.binding)
                .isEqualTo(AuthorizationPolicy.binding(SCOPE, "binding-1", "echo", Map.of("text", "hi")))
                .isEqualTo(ToolCallContracts.binding(SCOPE, "binding-1", accepted));
    }

    @Test
    void decodingFailuresArePreservedByName() {
        var decoded = new InferenceToolCalls.Call(
                "c1",
                "echo",
                Map.of(),
                null,
                InferenceToolCalls.Failure.invalid_arguments);
        assertThat(ToolCallContracts.accept(decoded, SCOPE, "b").inputError).isEqualTo("invalid_arguments");
    }

    @Test
    void inputValidationRejectsUnsupportedOrMalformedCalls() {
        var call = ToolCallContracts.accept(
                new InferenceToolCalls.Call("c1", "echo", Map.of("text", "hi"), null, null),
                SCOPE,
                "b");
        assertThatCode(() -> ToolCallContracts.validateInput(ECHO, call)).doesNotThrowAnyException();

        var wrongName = ToolCallContracts.accept(
                new InferenceToolCalls.Call("c1", "other", Map.of("text", "hi"), null, null),
                SCOPE,
                "b");
        assertThatThrownBy(() -> ToolCallContracts.validateInput(ECHO, wrongName))
                .hasMessage("Unsupported tool call");

        var toolset = ToolCallContracts.accept(
                new InferenceToolCalls.Call("c1", "echo", Map.of("text", "hi"), "remote", null),
                SCOPE,
                "b");
        assertThatThrownBy(() -> ToolCallContracts.validateInput(ECHO, toolset))
                .hasMessage("Unsupported tool call");

        var failed = ToolCallContracts.accept(
                new InferenceToolCalls.Call(
                        "c1",
                        "echo",
                        Map.of(),
                        null,
                        InferenceToolCalls.Failure.invalid_arguments),
                SCOPE,
                "b");
        assertThatThrownBy(() -> ToolCallContracts.validateInput(ECHO, failed))
                .hasMessage("Unsupported tool call");

        var tooLong = ToolCallContracts.accept(
                new InferenceToolCalls.Call("c1", "echo", Map.of("text", "too long"), null, null),
                SCOPE,
                "b");
        assertThatThrownBy(() -> ToolCallContracts.validateInput(ECHO, tooLong))
                .hasMessage("Tool value outside bounds");
    }

    @Test
    void resultsReferenceTheOriginalCallAndToolset() {
        var call = ToolCallContracts.accept(
                new InferenceToolCalls.Call("c1", "echo", Map.of("text", "hi"), null, null),
                SCOPE,
                "b");
        InferenceContent.ToolResult result = ToolCallContracts.result(call, "done", true);
        assertThat(result.toolUseId()).isEqualTo("c1");
        assertThat(result.error()).isTrue();
        assertThat(result.toolset()).isNull();
        assertThat(result.content()).singleElement()
                .isInstanceOfSatisfying(
                        InferenceContent.Text.class,
                        text -> assertThat(text.text()).isEqualTo("done"));
    }
}
