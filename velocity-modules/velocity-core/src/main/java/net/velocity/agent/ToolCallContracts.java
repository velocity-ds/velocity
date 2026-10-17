package net.velocity.agent;

import java.util.List;
import java.util.Objects;

import net.velocity.agent.state.AcceptedCall;
import net.velocity.agent.state.TrustedScope;
import net.velocity.inference.objects.InferenceContent;
import net.velocity.inference.objects.InferenceToolCalls;

/** Shared call mechanics only; dispatch, durable state and permission policy belong to each workflow. */
public final class ToolCallContracts {

    private ToolCallContracts() {
    }

    public static AcceptedCall accept(InferenceToolCalls.Call decoded, TrustedScope scope, String bindingId) {
        var accepted = new AcceptedCall();
        accepted.id = decoded.id();
        accepted.name = decoded.name();
        accepted.input = decoded.input();
        accepted.toolset = decoded.toolset();
        accepted.inputError = decoded.failure() == null ? null : decoded.failure().name();
        accepted.binding = binding(scope, bindingId, accepted);
        return accepted;
    }

    public static String binding(TrustedScope scope, String bindingId, AcceptedCall call) {
        return AuthorizationPolicy.binding(scope, bindingId, Objects.toString(call.name, ""), call.input);
    }

    public static void validateInput(ToolRegistry.Definition definition, AcceptedCall call) {
        if (call.inputError != null || call.toolset != null || !definition.name().equals(call.name)) {
            throw new IllegalArgumentException("Unsupported tool call");
        }
        JsonSchema.validate(definition.inputSchema(), call.input);
    }

    public static InferenceContent.ToolResult result(AcceptedCall call, String text, boolean error) {
        return new InferenceContent.ToolResult(
                call.id,
                List.of(new InferenceContent.Text(text)),
                error,
                call.toolset);
    }
}
