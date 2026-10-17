package net.velocity.agent.monitoring;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import net.velocity.agent.JsonSchema;
import net.velocity.agent.ToolRegistry;
import net.velocity.agent.persistence.ConflictException;
import net.velocity.agent.persistence.NotFoundException;
import net.velocity.agent.tools.ToolSchemas;
import net.velocity.inference.SystemPrompts;

import static net.velocity.agent.tools.ToolSchemas.*;

/** Generic monitoring definitions; tool discovery never grants scope or destination authority. */
public final class MonitoringTools {

    private MonitoringTools() {
    }

    public static ToolRegistry register(ToolRegistry registry, MonitoringService service) {
        Objects.requireNonNull(service);
        registry.register(
                definition(
                        "monitor_create",
                        SystemPrompts.MONITOR_CREATE_DESCRIPTION,
                        creationSchema(),
                        true,
                        service::create));
        registry.register(
                definition(
                        "monitor_list",
                        SystemPrompts.MONITOR_LIST_DESCRIPTION,
                        object(
                                Map.of("offset", integer(0, Integer.MAX_VALUE), "limit", integer(1, 100)),
                                List.of()),
                        false,
                        (context, input) -> service.list(
                                context.scope(),
                                number(input, "offset", 0),
                                number(input, "limit", 20))));
        registry.register(
                definition(
                        "monitor_get",
                        SystemPrompts.MONITOR_GET_DESCRIPTION,
                        object(Map.of("id", string(128)), List.of("id")),
                        false,
                        (context, input) -> service.get(context.scope(), (String) input.get("id"))));
        registry.register(
                definition(
                        "monitor_update",
                        SystemPrompts.MONITOR_UPDATE_DESCRIPTION,
                        updateSchema(),
                        true,
                        (context, input) -> service.update(context.scope(), input)));
        transition(
                registry,
                service,
                "monitor_pause",
                SystemPrompts.MONITOR_PAUSE_DESCRIPTION,
                MonitoringJob.Status.PAUSED);
        transition(
                registry,
                service,
                "monitor_resume",
                SystemPrompts.MONITOR_RESUME_DESCRIPTION,
                MonitoringJob.Status.ACTIVE);
        transition(
                registry,
                service,
                "monitor_close",
                SystemPrompts.MONITOR_CLOSE_DESCRIPTION,
                MonitoringJob.Status.CLOSED);
        registry.register(
                definition(
                        "monitor_run_now",
                        SystemPrompts.MONITOR_RUN_NOW_DESCRIPTION,
                        controlSchema(),
                        true,
                        (context, input) -> service.runNow(
                                context.scope(),
                                (String) input.get("id"),
                                ((Number) input.get("expected_revision")).longValue())));
        return registry;
    }

    private static void transition(
            ToolRegistry registry,
            MonitoringService service,
            String name,
            String description,
            MonitoringJob.Status status) {
        registry.register(
                definition(
                        name,
                        description,
                        controlSchema(),
                        true,
                        (context, input) -> service.transition(
                                context.scope(),
                                (String) input.get("id"),
                                ((Number) input.get("expected_revision")).longValue(),
                                status)));
    }

    private static ToolRegistry.Definition definition(
            String name,
            String description,
            Map<String, Object> schema,
            boolean write,
            ToolRegistry.Handler handler) {
        return new ToolRegistry.Definition(
                name,
                description,
                schema,
                outputSchema(),
                write ? "repository.write" : "repository.read",
                true,
                true,
                false,
                0,
                ToolRegistry.MonitoringAccess.denied,
                (context, input) -> {
                    JsonSchema.validate(schema, input);
                    try {
                        return handler.execute(context, input);
                    } catch (NotFoundException failure) {
                        return error("not_found", failure);
                    } catch (ConflictException failure) {
                        return error("revision_conflict", failure);
                    } catch (SecurityException failure) {
                        return error("not_authorized", failure);
                    } catch (IllegalArgumentException failure) {
                        return error("invalid_request", failure);
                    } catch (MonitoringService.UnavailableException failure) {
                        return error("unavailable", failure);
                    }
                });
    }

    public static Map<String, Object> creationSchema() {
        return object(definitionFields(), List.of("name", "original_request", "objective"));
    }

    public static Map<String, Object> updateSchema() {
        var fields = definitionFields();
        fields.remove("original_request");
        fields.put("id", string(128));
        fields.put("expected_revision", integer(1, Long.MAX_VALUE - 1));
        fields.put("clear_destination", Map.of("type", "boolean"));
        fields.put("clear_completion_rule", Map.of("type", "boolean"));
        return object(fields, List.of("id", "expected_revision"));
    }

    public static Map<String, Object> controlSchema() {
        return object(
                Map.of("id", string(128), "expected_revision", integer(1, Long.MAX_VALUE - 1)),
                List.of("id", "expected_revision"));
    }

    private static LinkedHashMap<String, Object> definitionFields() {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("name", string(200));
        fields.put("original_request", string(16000));
        fields.put("objective", string(16000));
        for (String field : List.of(
                "criteria",
                "exclusions",
                "sources",
                "notification_instructions",
                "completion_condition")) {
            fields.put(field, text(16000));
        }
        fields.put("timing", timingSchema());
        fields.put(
                "notification_policy",
                Map.of("type", "string", "enum", List.of("changes_only", "digest")));
        fields.put("digest_timing", timingSchema());
        fields.put("initial_summary", Map.of("type", "boolean"));
        fields.put("end_at", text(128));
        fields.put(
                "completion_rule",
                object(
                        Map.of(
                                "source_id",
                                string(500),
                                "item_id",
                                string(1000),
                                "fact",
                                string(200),
                                "equals",
                                text(2000)),
                        List.of("source_id", "item_id", "fact", "equals")));
        fields.put("destination", destinationSchema());
        return fields;
    }

    private static Map<String, Object> timingSchema() {
        return object(Map.of("cron", string(200), "timezone", string(100)), List.of("cron"));
    }

    private static Map<String, Object> metadata() {
        var fields = definitionFields();
        fields.put("id", string(128));
        fields.put("revision", integer(1, Long.MAX_VALUE));
        fields.put("status", string(64));
        fields.put("health", text(200));
        fields.put("working_summary", text(16000));
        fields.put("timezone", string(100));
        fields.put("initial_check", text(1000));
        fields.put(
                "destination",
                object(
                        Map.of(
                                "transport",
                                Map.of("type", "string", "enum", List.of("slack", "console")),
                                "channel_id",
                                string(128),
                                "thread_ts",
                                string(64)),
                        List.of("transport")));
        for (String field : List.of(
                "schedule_id",
                "next_due",
                "last_success",
                "created_at",
                "updated_at",
                "closed_at",
                "registration",
                "registration_failure",
                "run_id",
                "execution_id",
                "next_digest",
                "active_run_id",
                "last_check")) {
            fields.put(field, text(1000));
        }
        for (String field : List.of("questions", "limitations")) {
            fields.put(field, Map.of("type", "array", "maxItems", 100, "items", text(16000)));
        }
        return fields;
    }

    private static Map<String, Object> outputSchema() {
        var fields = new LinkedHashMap<>(metadata());
        fields.put("message", text(16000));
        fields.put("current_revision", integer(1, Long.MAX_VALUE));
        fields.put(
                "jobs",
                Map.of(
                        "type",
                        "array",
                        "maxItems",
                        100,
                        "items",
                        object(metadata(), List.of("id", "revision", "status"))));
        for (String field : List.of("offset", "limit", "next_offset")) {
            fields.put(field, integer(-1, Integer.MAX_VALUE));
        }
        fields.put("has_more", Map.of("type", "boolean"));
        return object(fields, List.of("status"));
    }

    private static Map<String, Object> error(String status, RuntimeException failure) {
        return Map.of(
                "status",
                status,
                "message",
                failure.getMessage() == null ? status : failure.getMessage());
    }

    private static int number(Map<String, Object> input, String key, int defaultValue) {
        return ((Number) input.getOrDefault(key, defaultValue)).intValue();
    }
}
