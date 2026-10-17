package net.velocity.agent.history;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.velocity.agent.JsonSchema;
import net.velocity.agent.ToolRegistry;
import net.velocity.agent.state.ExecutionStatus;
import net.velocity.agent.tools.ToolSchemas;
import net.velocity.inference.SystemPrompts;

import static net.velocity.agent.tools.ToolSchemas.*;

/** Read-only history tools; authority always comes from the execution context, never model input. */
public final class TaskHistoryTools {

    private TaskHistoryTools() {
    }

    public static ToolRegistry register(ToolRegistry registry, TaskHistoryService service) {
        registry.register(
                definition(
                        "task_list",
                        SystemPrompts.TASK_LIST_DESCRIPTION,
                        object(
                                Map.of(
                                        "status",
                                        statuses(),
                                        "offset",
                                        integer(0, 1_000_000),
                                        "limit",
                                        integer(1, TaskHistoryService.MAX_PAGE)),
                                List.of()),
                        listOutput(),
                        service::list));
        registry.register(
                definition(
                        "task_get",
                        SystemPrompts.TASK_GET_DESCRIPTION,
                        object(
                                Map.of(
                                        "execution_id",
                                        string(128),
                                        "field",
                                        enumeration("request", "goal", "answer"),
                                        "offset",
                                        integer(0, Integer.MAX_VALUE),
                                        "limit",
                                        integer(1, TaskHistoryService.MAX_CHUNK),
                                        "expected_revision",
                                        integer(1, Long.MAX_VALUE)),
                                List.of("execution_id")),
                        getOutput(),
                        service::get));
        if (!service.searchEnabled()) {
            return registry;
        }
        registry.register(
                definition(
                        "task_search",
                        SystemPrompts.TASK_SEARCH_DESCRIPTION,
                        object(
                                Map.of(
                                        "query",
                                        string(200),
                                        "offset",
                                        integer(0, 1_000_000),
                                        "limit",
                                        integer(1, TaskHistoryService.MAX_PAGE)),
                                List.of("query")),
                        transcriptOutput(),
                        service::search));
        registry.register(
                definition(
                        "task_messages",
                        SystemPrompts.TASK_MESSAGES_DESCRIPTION,
                        object(
                                Map.of(
                                        "execution_id",
                                        string(128),
                                        "offset",
                                        integer(0, 1_000_000),
                                        "limit",
                                        integer(1, TaskHistoryService.MAX_PAGE),
                                        "expected_revision",
                                        integer(1, Long.MAX_VALUE)),
                                List.of("execution_id")),
                        transcriptOutput(),
                        service::messages));
        registry.register(
                definition(
                        "task_message_window",
                        SystemPrompts.TASK_MESSAGE_WINDOW_DESCRIPTION,
                        object(
                                Map.of(
                                        "execution_id",
                                        string(128),
                                        "message_id",
                                        string(128),
                                        "radius",
                                        integer(0, 9),
                                        "expected_revision",
                                        integer(1, Long.MAX_VALUE)),
                                List.of("execution_id", "message_id")),
                        transcriptOutput(),
                        service::window));
        return registry;
    }

    private static Map<String, Object> transcriptOutput() {
        return Map.of(
                "type",
                "object",
                "properties",
                Map.of(
                        "status",
                        enumeration(
                                "ok",
                                "found",
                                "disabled",
                                "not_found",
                                "revision_conflict",
                                "invalid_request"),
                        "content_trust",
                        enumeration("untrusted_data")),
                "required",
                List.of("status", "content_trust"),
                "additionalProperties",
                true);
    }

    private static ToolRegistry.Definition definition(
            String name,
            String description,
            Map<String, Object> input,
            Map<String, Object> output,
            ToolRegistry.Handler handler) {
        return new ToolRegistry.Definition(
                name,
                description,
                input,
                output,
                "repository.read",
                true,
                true,
                false,
                0,
                ToolRegistry.MonitoringAccess.observation,
                (context, arguments) -> {
                    JsonSchema.validate(input, arguments);
                    try {
                        return handler.execute(context, arguments);
                    } catch (TaskHistoryService.InvalidRequestException invalid) {
                        return Map.of(
                                "status",
                                "invalid_request",
                                "message",
                                invalid.getMessage(),
                                "content_trust",
                                "untrusted_data");
                    }
                });
    }

    private static Map<String, Object> listOutput() {
        var fields = common("ok", "invalid_request");
        fields.put(
                "tasks",
                Map.of("type", "array", "items", summary(), "maxItems", TaskHistoryService.MAX_PAGE));
        fields.put("offset", integer(0, 1_000_000));
        fields.put("has_more", Map.of("type", "boolean"));
        fields.put("next_offset", integer(-1, 1_000_000));
        fields.put("pagination_limit_reached", Map.of("type", "boolean"));
        return object(fields, List.of("status", "content_trust"));
    }

    private static Map<String, Object> getOutput() {
        var fields = common("found", "not_found", "revision_conflict", "invalid_request");
        fields.put("task", summary());
        fields.put("field", enumeration("request", "goal", "answer"));
        fields.put("content_chunk", text(TaskHistoryService.MAX_CHUNK + 1));
        fields.put("offset", integer(0, Integer.MAX_VALUE));
        fields.put("total_chars", integer(0, Integer.MAX_VALUE));
        fields.put("next_offset", integer(-1, Integer.MAX_VALUE));
        fields.put("complete", Map.of("type", "boolean"));
        fields.put("current_revision", integer(1, Long.MAX_VALUE));
        return object(fields, List.of("status", "content_trust"));
    }

    private static LinkedHashMap<String, Object> common(String... statuses) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("status", enumeration(statuses));
        fields.put("content_trust", enumeration("untrusted_data"));
        fields.put("message", text(300));
        return fields;
    }

    private static Map<String, Object> summary() {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("execution_id", string(128));
        fields.put("status", statuses());
        fields.put("completion_basis", enumeration("model_response"));
        fields.put("revision", integer(1, Long.MAX_VALUE));
        fields.put("created_at", string(64));
        fields.put("updated_at", string(64));
        fields.put("request_preview", text(200));
        fields.put("answer_preview", text(400));
        fields.put("request_truncated", Map.of("type", "boolean"));
        fields.put("answer_truncated", Map.of("type", "boolean"));
        return object(fields, List.copyOf(fields.keySet()));
    }

    private static Map<String, Object> statuses() {
        return enumeration(Arrays.stream(ExecutionStatus.values()).map(Enum::name).toArray(String[]::new));
    }
}
