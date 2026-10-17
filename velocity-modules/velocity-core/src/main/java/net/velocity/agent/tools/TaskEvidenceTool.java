package net.velocity.agent.tools;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.json.JsonMapper;

import net.velocity.agent.JsonSchema;
import net.velocity.agent.ToolRegistry;
import net.velocity.agent.persistence.ExecutionRepository;
import net.velocity.agent.persistence.NotFoundException;
import net.velocity.inference.SystemPrompts;

import static net.velocity.agent.tools.ToolSchemas.*;

/** The model can reread bounded evidence only from its current trusted execution. */
public final class TaskEvidenceTool {

    public static final int MAX_CHUNK = 4000;
    private static final JsonMapper JSON = new JsonMapper();

    private TaskEvidenceTool() {
    }

    public static ToolRegistry register(ToolRegistry registry, ExecutionRepository repository) {
        var input = object(
                Map.of(
                        "source",
                        enumeration("artifact", "message"),
                        "artifact",
                        string(132),
                        "message_sequence",
                        integer(1, Integer.MAX_VALUE),
                        "offset",
                        integer(0, Integer.MAX_VALUE),
                        "limit",
                        integer(1, MAX_CHUNK)),
                "source");
        var output = object(
                Map.ofEntries(
                        Map.entry("status", enumeration("found", "not_found", "invalid_request")),
                        Map.entry("content_trust", enumeration("untrusted_data")),
                        Map.entry("source", enumeration("artifact", "message")),
                        Map.entry("format", enumeration("tool_artifact_text", "canonical_message_json")),
                        Map.entry("content_chunk", text(MAX_CHUNK + 1)),
                        Map.entry("offset", integer(0, Integer.MAX_VALUE)),
                        Map.entry("next_offset", integer(-1, (long) Integer.MAX_VALUE + MAX_CHUNK)),
                        Map.entry("complete", bool())),
                "status",
                "content_trust");
        return registry.register(
                new ToolRegistry.Definition(
                        "task_evidence_read",
                        SystemPrompts.TASK_EVIDENCE_READ_DESCRIPTION,
                        input,
                        output,
                        "repository.read",
                        true,
                        true,
                        false,
                        10_000,
                        ToolRegistry.MonitoringAccess.observation,
                        (context, arguments) -> {
                            JsonSchema.validate(input, arguments);
                            String source = (String) arguments.get("source");
                            boolean artifact = source.equals("artifact");
                            if (artifact != arguments.containsKey("artifact")
                                    || artifact == arguments.containsKey("message_sequence")) {
                                return status("invalid_request");
                            }
                            if (context.childRunId() != null) {
                                return status("not_found");
                            }
                            context.cancellation().check();
                            try {
                                var execution = repository.get(context.executionId());
                                if (!Objects.equals(
                                        context.scope().requireOwnerAccountId(),
                                        execution.scope.requireOwnerAccountId())) {
                                    return status("not_found");
                                }
                                int offset = ((Number) arguments.getOrDefault("offset", 0)).intValue();
                                int limit = ((Number) arguments.getOrDefault("limit", MAX_CHUNK)).intValue();
                                int begin = Math.max(0, offset - 1);
                                String data;
                                if (artifact) {
                                    String reference = (String) arguments.get("artifact");
                                    if (!reference.matches("[A-Za-z0-9_-]{1,128}\\.txt")) {
                                        return status("invalid_request");
                                    }
                                    data = repository
                                            .artifact(context.executionId(), reference, begin, limit + 3);
                                } else {
                                    int sequence = ((Number) arguments.get("message_sequence")).intValue();
                                    if (sequence > execution.messages.size()) {
                                        return status("not_found");
                                    }
                                    String message = JSON
                                            .writeValueAsString(execution.messages.get(sequence - 1));
                                    data = message.substring(
                                            Math.min(begin, message.length()),
                                            (int) Math.min(message.length(), (long) begin + limit + 3));
                                }
                                context.cancellation().check();
                                int start = Math.min(offset - begin, data.length());
                                if (start > 0 && start < data.length()
                                        && Character.isLowSurrogate(data.charAt(start))
                                        && Character.isHighSurrogate(data.charAt(start - 1))) {
                                    start--;
                                }
                                int end = Math.min(data.length(), start + limit);
                                if (end > start && end < data.length()
                                        && Character.isHighSurrogate(data.charAt(end - 1))
                                        && Character.isLowSurrogate(data.charAt(end))) {
                                    if (end - start == 1) {
                                        end++;
                                    } else {
                                        end--;
                                    }
                                }
                                boolean complete = end == data.length();
                                var result = new LinkedHashMap<String, Object>(status("found"));
                                result.put("source", source);
                                result.put(
                                        "format",
                                        artifact ? "tool_artifact_text" : "canonical_message_json");
                                result.put("content_chunk", data.substring(start, end));
                                result.put("offset", begin + start);
                                result.put("next_offset", complete ? -1L : (long) begin + end);
                                result.put("complete", complete);
                                return result;
                            } catch (NotFoundException missing) {
                                return status("not_found");
                            }
                        }));
    }

    private static Map<String, Object> status(String status) {
        return Map.of("status", status, "content_trust", "untrusted_data");
    }
}
