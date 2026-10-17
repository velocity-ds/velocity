package net.velocity.inference.objects;

import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.json.JsonMapper;

/** Lossless interpretation of tool interactions; decoding never authorizes execution. */
public final class InferenceToolCalls {

    private static final JsonMapper JSON = new JsonMapper();
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

    public sealed interface Interaction permits Call, Result, UnsupportedAction, Other {}

    public enum Failure {
        invalid_arguments, unsupported_call_provenance, unsupported_tool
    }

    public enum IdentityFailure {
        empty_id, duplicate_id
    }

    /** Immutable interpretation; the original provider content remains the replay authority. */
    public record Call(String id, String name, Map<String, Object> input, String toolset, Failure failure)
            implements
                Interaction {

        public Call {
            input = InferenceJson.copy(input);
        }
    }

    public record Result(String id) implements Interaction {}

    public record UnsupportedAction(String type) implements Interaction {

        public boolean call() {
            return type.endsWith("_call") || type.endsWith("tool_use") || type.equals("mcp_approval_request");
        }
    }

    public enum Other implements Interaction {
        INSTANCE
    }

    private InferenceToolCalls() {
    }

    public static Interaction decode(InferenceContent content) {
        if (content instanceof InferenceContent.ToolCall call) {
            return call(call.id(), call.name(), call.input(), call.toolset(), null);
        }
        if (content instanceof InferenceContent.ToolResult result) {
            return new Result(result.toolUseId());
        }
        if (!(content instanceof InferenceContent.ProviderBlock block)) {
            return Other.INSTANCE;
        }
        return switch (block.type()) {
            case "function_call", "tool_use" -> nativeCall(block);
            case "function_call_output" -> new Result(string(block.data().get("call_id")));
            case "tool_result" -> new Result(string(block.data().get("tool_use_id")));
            case "mcp_approval_request", "mcp_approval_response" -> new UnsupportedAction(block.type());
            default -> actionType(block.type()) ? new UnsupportedAction(block.type()) : Other.INSTANCE;
        };
    }

    /** The caller owns the identity set, including any prior turns. Invalid identities are not added. */
    public static IdentityFailure registerIdentity(String id, Set<String> identities) {
        if (id == null || id.isBlank()) {
            return IdentityFailure.empty_id;
        }
        return identities.add(id) ? null : IdentityFailure.duplicate_id;
    }

    private static Call nativeCall(InferenceContent.ProviderBlock block) {
        var data = block.data();
        boolean responses = "function_call".equals(block.type());
        String id = string(data.get(responses ? "call_id" : "id"));
        String name = string(data.get("name"));
        String toolset = string(data.get("toolset_name"));
        Failure failure = data.get("caller") != null && !Map.of("type", "direct").equals(data.get("caller"))
                ? Failure.unsupported_call_provenance
                : null;
        try {
            Object value = data.get(responses ? "arguments" : "input");
            Map<String, Object> input = value instanceof String raw
                    ? JSON.readValue(raw, OBJECT)
                    : JSON.convertValue(value, OBJECT);
            if (input == null) {
                throw new IllegalArgumentException("Missing arguments");
            }
            // Immutable JSON validation (including finite numbers) is part of argument decoding.
            return call(id, name, input, toolset, failure);
        } catch (Exception invalid) {
            return call(id, name, Map.of(), toolset, Failure.invalid_arguments);
        }
    }

    private static Call call(
            String id,
            String name,
            Map<String, Object> input,
            String toolset,
            Failure failure) {
        if (name == null || name.isBlank() || toolset != null) {
            failure = Failure.unsupported_tool;
        }
        return new Call(id, name, input, toolset, failure);
    }

    private static boolean actionType(String type) {
        return type.endsWith("_call") || type.endsWith("_output") || type.endsWith("tool_use")
                || type.endsWith("tool_result");
    }

    private static String string(Object value) {
        return value instanceof String text ? text : null;
    }
}
