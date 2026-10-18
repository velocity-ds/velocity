package net.velocity.inference.objects;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InferenceToolCallsTest {

    @Test
    void portableToolCallsDecodeDirectly() {
        var content = new InferenceContent.ToolCall("c1", "echo", Map.of("text", "hi"), null);
        var decoded = InferenceToolCalls.decode(content);
        assertThat(decoded).isInstanceOfSatisfying(InferenceToolCalls.Call.class, call -> {
            assertThat(call.id()).isEqualTo("c1");
            assertThat(call.name()).isEqualTo("echo");
            assertThat(call.input()).containsExactly(Map.entry("text", "hi"));
            assertThat(call.toolset()).isNull();
            assertThat(call.failure()).isNull();
        });
    }

    @Test
    void toolsetCallsAreUnsupported() {
        var content = new InferenceContent.ToolCall("c1", "echo", Map.of(), "remote");
        assertThat(InferenceToolCalls.decode(content)).isInstanceOfSatisfying(
                InferenceToolCalls.Call.class,
                call -> assertThat(call.failure()).isEqualTo(InferenceToolCalls.Failure.unsupported_tool));
    }

    @Test
    void responsesFunctionCallsCarryStringArguments() {
        var block = new InferenceContent.ProviderBlock(
                "openai",
                "function_call",
                Map.of("type", "function_call", "call_id", "fc1", "name", "echo", "arguments", "{\"a\":1}"));
        assertThat(InferenceToolCalls.decode(block))
                .isInstanceOfSatisfying(InferenceToolCalls.Call.class, call -> {
                    assertThat(call.id()).isEqualTo("fc1");
                    assertThat(call.name()).isEqualTo("echo");
                    assertThat(call.input()).containsExactly(Map.entry("a", 1));
                    assertThat(call.failure()).isNull();
                });
    }

    @Test
    void claudeToolUseCarriesObjectInput() {
        var block = new InferenceContent.ProviderBlock(
                "claude",
                "tool_use",
                Map.of("type", "tool_use", "id", "tu1", "name", "echo", "input", Map.of("a", List.of(1, 2))));
        assertThat(InferenceToolCalls.decode(block))
                .isInstanceOfSatisfying(InferenceToolCalls.Call.class, call -> {
                    assertThat(call.id()).isEqualTo("tu1");
                    assertThat(call.input()).containsExactly(Map.entry("a", List.of(1, 2)));
                    assertThat(call.failure()).isNull();
                });
    }

    @Test
    void malformedArgumentsAndForeignProvenanceAreReportedNotThrown() {
        var malformed = new InferenceContent.ProviderBlock(
                "openai",
                "function_call",
                Map.of("type", "function_call", "call_id", "fc1", "name", "echo", "arguments", "not json"));
        assertThat(InferenceToolCalls.decode(malformed))
                .isInstanceOfSatisfying(InferenceToolCalls.Call.class, call -> {
                    assertThat(call.failure()).isEqualTo(InferenceToolCalls.Failure.invalid_arguments);
                    assertThat(call.input()).isEmpty();
                });
        var nameless = new InferenceContent.ProviderBlock(
                "openai",
                "function_call",
                Map.of("type", "function_call", "call_id", "fc1", "arguments", "{}"));
        assertThat(InferenceToolCalls.decode(nameless)).isInstanceOfSatisfying(
                InferenceToolCalls.Call.class,
                call -> assertThat(call.failure()).isEqualTo(InferenceToolCalls.Failure.unsupported_tool));
        var foreign = new InferenceContent.ProviderBlock(
                "openai",
                "function_call",
                Map.of(
                        "type",
                        "function_call",
                        "call_id",
                        "fc1",
                        "name",
                        "echo",
                        "arguments",
                        "{}",
                        "caller",
                        Map.of("type", "server")));
        assertThat(InferenceToolCalls.decode(foreign)).isInstanceOfSatisfying(
                InferenceToolCalls.Call.class,
                call -> assertThat(call.failure())
                        .isEqualTo(InferenceToolCalls.Failure.unsupported_call_provenance));
        var direct = new InferenceContent.ProviderBlock(
                "openai",
                "function_call",
                Map.of(
                        "type",
                        "function_call",
                        "call_id",
                        "fc1",
                        "name",
                        "echo",
                        "arguments",
                        "{}",
                        "caller",
                        Map.of("type", "direct")));
        assertThat(InferenceToolCalls.decode(direct)).isInstanceOfSatisfying(
                InferenceToolCalls.Call.class,
                call -> assertThat(call.failure()).isNull());
    }

    @Test
    void resultsAndOtherBlocksAreClassified() {
        assertThat(
                InferenceToolCalls.decode(
                        new InferenceContent.ToolResult(
                                "c1",
                                List.of(new InferenceContent.Text("ok")),
                                false,
                                null)))
                .isEqualTo(new InferenceToolCalls.Result("c1"));
        assertThat(
                InferenceToolCalls.decode(
                        new InferenceContent.ProviderBlock(
                                "openai",
                                "function_call_output",
                                Map.of("type", "function_call_output", "call_id", "fc1", "output", "x"))))
                .isEqualTo(new InferenceToolCalls.Result("fc1"));
        assertThat(
                InferenceToolCalls.decode(
                        new InferenceContent.ProviderBlock(
                                "claude",
                                "tool_result",
                                Map.of("type", "tool_result", "tool_use_id", "tu1"))))
                .isEqualTo(new InferenceToolCalls.Result("tu1"));
        assertThat(InferenceToolCalls.decode(new InferenceContent.Text("hello")))
                .isEqualTo(InferenceToolCalls.Other.INSTANCE);
        assertThat(
                InferenceToolCalls.decode(
                        new InferenceContent.ProviderBlock(
                                "openai",
                                "reasoning",
                                Map.of("type", "reasoning"))))
                .isEqualTo(InferenceToolCalls.Other.INSTANCE);
    }

    @Test
    void unsupportedActionsDistinguishCallsFromOutputs() {
        var approval = InferenceToolCalls.decode(
                new InferenceContent.ProviderBlock(
                        "openai",
                        "mcp_approval_request",
                        Map.of("type", "mcp_approval_request")));
        assertThat(approval).isInstanceOfSatisfying(
                InferenceToolCalls.UnsupportedAction.class,
                action -> assertThat(action.call()).isTrue());
        var search = InferenceToolCalls.decode(
                new InferenceContent.ProviderBlock(
                        "openai",
                        "web_search_call",
                        Map.of("type", "web_search_call")));
        assertThat(search).isInstanceOfSatisfying(
                InferenceToolCalls.UnsupportedAction.class,
                action -> assertThat(action.call()).isTrue());
        var output = InferenceToolCalls.decode(
                new InferenceContent.ProviderBlock(
                        "openai",
                        "image_generation_output",
                        Map.of("type", "image_generation_output")));
        assertThat(output).isInstanceOfSatisfying(
                InferenceToolCalls.UnsupportedAction.class,
                action -> assertThat(action.call()).isFalse());
    }

    @Test
    void identitiesMustBeNonBlankAndUnique() {
        var identities = new HashSet<String>();
        assertThat(InferenceToolCalls.registerIdentity("c1", identities)).isNull();
        assertThat(InferenceToolCalls.registerIdentity("c1", identities))
                .isEqualTo(InferenceToolCalls.IdentityFailure.duplicate_id);
        assertThat(InferenceToolCalls.registerIdentity(" ", identities))
                .isEqualTo(InferenceToolCalls.IdentityFailure.empty_id);
        assertThat(InferenceToolCalls.registerIdentity(null, identities))
                .isEqualTo(InferenceToolCalls.IdentityFailure.empty_id);
        assertThat(identities).containsExactly("c1");
    }
}
