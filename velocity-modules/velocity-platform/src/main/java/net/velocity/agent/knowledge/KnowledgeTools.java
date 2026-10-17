package net.velocity.agent.knowledge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.velocity.agent.JsonSchema;
import net.velocity.agent.ToolRegistry;
import net.velocity.agent.tools.ToolSchemas;
import net.velocity.inference.SystemPrompts;

import static net.velocity.agent.tools.ToolSchemas.*;

/** Schemas stay within the gateway's deliberately small JSON Schema dialect. */
public final class KnowledgeTools {

    private KnowledgeTools() {
    }

    private static final Map<String, Map<String, Object>> INPUT_SCHEMAS = inputSchemas();

    /** Schemas shared by tool dispatch and trusted operator adapters, without execution handlers. */
    public static Map<String, Object> inputSchema(String name) {
        return java.util.Objects.requireNonNull(INPUT_SCHEMAS.get(name), "Unknown tool schema: " + name);
    }

    private static Map<String, Map<String, Object>> inputSchemas() {
        var schemas = new LinkedHashMap<String, Map<String, Object>>();
        schemas.put(
                "data_save",
                object(
                        Map.of(
                                "dataset",
                                string(64),
                                "key",
                                string(128),
                                "title",
                                string(200),
                                "description",
                                text(1000),
                                "tags",
                                array(string(64), 20),
                                "content",
                                Map.of("type", "object", "additionalProperties", true),
                                "expected_revision",
                                integer(0, Long.MAX_VALUE - 1)),
                        List.of(
                                "dataset",
                                "key",
                                "title",
                                "description",
                                "tags",
                                "content",
                                "expected_revision")));
        schemas.put(
                "data_update",
                object(
                        Map.of(
                                "id",
                                string(64),
                                "dataset",
                                string(64),
                                "key",
                                string(128),
                                "expected_revision",
                                integer(1, Long.MAX_VALUE - 1),
                                "set",
                                Map.of("type", "object", "additionalProperties", true),
                                "unset",
                                array(string(128), 50),
                                "append",
                                Map.of("type", "object", "additionalProperties", true),
                                "title",
                                string(200),
                                "description",
                                text(1000),
                                "tags",
                                array(string(64), 20)),
                        List.of("expected_revision")));
        schemas.put(
                "data_delete",
                object(
                        Map.of(
                                "id",
                                string(64),
                                "dataset",
                                string(64),
                                "key",
                                string(128),
                                "expected_revision",
                                integer(1, Long.MAX_VALUE - 1)),
                        List.of("expected_revision")));
        schemas.put(
                "data_get",
                object(
                        Map.of(
                                "id",
                                string(64),
                                "dataset",
                                string(64),
                                "key",
                                string(128),
                                "expected_revision",
                                integer(1, Long.MAX_VALUE),
                                "offset",
                                integer(0, 2_000_000),
                                "limit",
                                integer(1, 4000)),
                        List.of()));
        schemas.put(
                "data_query",
                object(
                        Map.of(
                                "dataset",
                                string(64),
                                "filters",
                                Map.of("type", "object", "additionalProperties", true),
                                "fields",
                                array(string(128), 20),
                                "sort_by",
                                sortSchema(),
                                "descending",
                                Map.of("type", "boolean"),
                                "offset",
                                integer(0, 1_000_000),
                                "limit",
                                integer(1, 100)),
                        List.of("dataset")));
        schemas.put(
                "data_aggregate",
                object(
                        Map.of(
                                "dataset",
                                string(64),
                                "filters",
                                Map.of("type", "object", "additionalProperties", true),
                                "operation",
                                enumeration("count", "sum", "avg", "min", "max"),
                                "field",
                                string(128),
                                "group_by",
                                string(128),
                                "group_limit",
                                integer(1, 100)),
                        List.of("dataset", "operation")));
        schemas.put(
                "data_catalog",
                object(
                        Map.of(
                                "mode",
                                Map.of("type", "string", "enum", List.of("records", "datasets")),
                                "dataset",
                                string(64),
                                "search",
                                string(100),
                                "offset",
                                integer(0, 1_000_000),
                                "limit",
                                integer(1, 100),
                                "after",
                                string(64)),
                        List.of()));
        return Map.copyOf(schemas);
    }

    /** The write tools; each pauses for the requester's confirmation with a trusted preview when asked to. */
    public static final List<String> WRITE_TOOLS = List.of("data_save", "data_update", "data_delete");

    /**
     * @param confirmWrites when true, every write tool is registered with a
     *        {@link ToolRegistry.ConfirmingHandler}, so the call waits for the requester and the preview
     *        rendered from its arguments is shown to them
     */
    public static ToolRegistry register(
            ToolRegistry registry,
            KnowledgeService service,
            boolean confirmWrites) {
        registry.register(
                write(
                        "data_save",
                        SystemPrompts.DATA_SAVE_DESCRIPTION,
                        service::save,
                        confirmWrites ? service::previewSave : null));
        registry.register(
                write(
                        "data_update",
                        SystemPrompts.DATA_UPDATE_DESCRIPTION,
                        service::update,
                        confirmWrites ? service::previewUpdate : null));
        registry.register(
                write(
                        "data_delete",
                        SystemPrompts.DATA_DELETE_DESCRIPTION,
                        service::delete,
                        confirmWrites ? service::previewDelete : null));
        registry.register(read("data_get", SystemPrompts.DATA_GET_DESCRIPTION, service::get));
        registry.register(read("data_query", SystemPrompts.DATA_QUERY_DESCRIPTION, service::query));
        registry.register(
                read("data_aggregate", SystemPrompts.DATA_AGGREGATE_DESCRIPTION, service::aggregate));
        registry.register(read("data_catalog", SystemPrompts.DATA_CATALOG_DESCRIPTION, service::catalog));
        return registry;
    }

    /** Renders a pending write for the requester from its validated input; may read, never writes. */
    @FunctionalInterface
    interface Preview {

        String render(ToolRegistry.Context context, Map<String, Object> input) throws Exception;
    }

    private static ToolRegistry.Definition read(
            String name,
            String description,
            ToolRegistry.Handler handler) {
        return definition(name, description, false, guarded(inputSchema(name), handler));
    }

    private static ToolRegistry.Definition write(
            String name,
            String description,
            ToolRegistry.Handler handler,
            Preview preview) {
        var schema = inputSchema(name);
        var guarded = guarded(schema, handler);
        if (preview == null) {
            return definition(name, description, true, guarded);
        }
        return definition(name, description, true, new ToolRegistry.ConfirmingHandler() {

            @Override
            public Object execute(ToolRegistry.Context context, Map<String, Object> input) throws Exception {
                return guarded.execute(context, input);
            }

            @Override
            public String preview(ToolRegistry.Context context, Map<String, Object> input) throws Exception {
                JsonSchema.validate(schema, input);
                return preview.render(context, input);
            }
        });
    }

    private static ToolRegistry.Handler guarded(Map<String, Object> schema, ToolRegistry.Handler handler) {
        return (context, input) -> {
            JsonSchema.validate(schema, input);
            try {
                return handler.execute(context, input);
            } catch (KnowledgeService.InvalidRequestException invalid) {
                return Map.of("status", "invalid_request", "message", invalid.getMessage());
            }
        };
    }

    private static ToolRegistry.Definition definition(
            String name,
            String description,
            boolean write,
            ToolRegistry.Handler handler) {
        return new ToolRegistry.Definition(
                name,
                description,
                inputSchema(name),
                outputSchema(name),
                write ? "repository.write" : "repository.read",
                true,
                true,
                false,
                0,
                write ? ToolRegistry.MonitoringAccess.denied : ToolRegistry.MonitoringAccess.observation,
                handler);
    }

    private static Map<String, Object> outputSchema(String name) {
        var properties = new LinkedHashMap<String, Object>();
        properties.put(
                "status",
                Map.of(
                        "type",
                        "string",
                        "enum",
                        List.of(
                                "saved",
                                "deleted",
                                "found",
                                "ok",
                                "not_found",
                                "revision_conflict",
                                "invalid_request")));
        properties.put("id", string(64));
        properties.put("current_revision", integer(1, Long.MAX_VALUE));
        properties.put("message", text(1000));
        if (WRITE_TOOLS.contains(name)) {
            properties.put("record", metadataSchema());
            properties.put("database_committed", Map.of("type", "boolean"));
        } else if (name.equals("data_get")) {
            properties.put("record", metadataSchema());
            properties.put("content", Map.of("type", "object", "additionalProperties", true));
            properties.put("content_trust", Map.of("type", "string", "enum", List.of("untrusted_data")));
            properties.put("format", Map.of("type", "string", "enum", List.of("json_text")));
            properties.put("offset", integer(0, 2_000_000));
            properties.put("total_chars", integer(0, 2_000_000));
            properties.put("next_offset", integer(-1, 2_000_000));
            properties.put("complete", Map.of("type", "boolean"));
            properties.put("content_chunk", text(4001));
            properties.put("retrieval", text(1000));
        } else if (name.equals("data_aggregate")) {
            properties.put("dataset", string(64));
            properties.put("operation", enumeration("count", "sum", "avg", "min", "max"));
            properties.put("field", string(128));
            properties.put("group_by", string(128));
            properties.put("count", integer(0, Long.MAX_VALUE));
            properties.put("value", Map.of("type", "number"));
            properties.put("skipped_non_numeric", integer(0, Long.MAX_VALUE));
            properties.put(
                    "groups",
                    array(
                            object(
                                    Map.of(
                                            "group",
                                            Map.of("type", "string"),
                                            "count",
                                            integer(0, Long.MAX_VALUE),
                                            "value",
                                            Map.of("type", "number"),
                                            "skipped_non_numeric",
                                            integer(0, Long.MAX_VALUE)),
                                    List.of("count", "skipped_non_numeric")),
                            100));
            properties.put("group_limit_reached", bool());
        } else {
            properties.put("records", array(metadataSchema(), 100));
            properties.put("offset", integer(0, 1_000_000));
            properties.put("has_more", Map.of("type", "boolean"));
            properties.put("next_offset", integer(0, 1_000_000));
            properties.put("pagination_limit_reached", Map.of("type", "boolean"));
            properties.put("retrieval", text(1000));
            if (name.equals("data_catalog")) {
                properties.put("next_after", string(64));
                properties.put(
                        "datasets",
                        array(
                                object(
                                        Map.of(
                                                "dataset",
                                                string(64),
                                                "location",
                                                string(300),
                                                "schema_status",
                                                Map.of("type", "string", "enum", List.of("observed_only")),
                                                "observation_scope",
                                                Map.of("type", "string", "enum", List.of("latest_record")),
                                                "retrieval",
                                                text(1000),
                                                "sample_record",
                                                metadataSchema()),
                                        List.of(
                                                "dataset",
                                                "location",
                                                "schema_status",
                                                "observation_scope",
                                                "retrieval")),
                                100));
            }
        }
        return object(properties, List.of("status"));
    }

    private static Map<String, Object> metadataSchema() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("id", string(64));
        properties.put("dataset", string(64));
        properties.put("key", string(128));
        properties.put("title", string(200));
        properties.put("description", text(1000));
        properties.put("tags", array(string(64), 20));
        properties.put("location", string(300));
        properties.put("revision", integer(1, Long.MAX_VALUE));
        properties.put("created_at", string(64));
        properties.put("updated_at", string(64));
        properties.put("created_by_execution_id", Map.of("type", "string"));
        properties.put("updated_by_execution_id", Map.of("type", "string"));
        properties.put("observed_fields", array(string(128), 64));
        properties.put("observed_fields_complete", Map.of("type", "boolean"));
        properties.put("schema_status", Map.of("type", "string", "enum", List.of("observed_only")));
        var required = List.copyOf(properties.keySet());
        properties.put("selected_fields", Map.of("type", "object", "additionalProperties", true));
        properties.put("selected_fields_omitted", Map.of("type", "boolean"));
        return object(properties, required);
    }

    private static Map<String, Object> sortSchema() {
        return Map.of(
                "type",
                "string",
                "enum",
                List.of("dataset", "key", "title", "created_at", "updated_at", "revision"));
    }
}
