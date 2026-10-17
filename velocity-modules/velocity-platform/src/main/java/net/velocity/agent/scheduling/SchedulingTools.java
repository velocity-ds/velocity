package net.velocity.agent.scheduling;

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

/** Scheduling mutations remain ordinary, scoped agent tool calls. */
public final class SchedulingTools {

    private SchedulingTools() {
    }

    private static final Map<String, Map<String, Object>> INPUT_SCHEMAS = Map.of(
            "schedule_execution",
            creationSchema(false),
            "schedule_message",
            creationSchema(true),
            "schedule_list",
            object(Map.of(), List.of()),
            "schedule_get",
            object(Map.of("id", string(128)), List.of("id")),
            "schedule_update",
            updateSchema(),
            "schedule_pause",
            transitionSchema(),
            "schedule_resume",
            transitionSchema(),
            "schedule_cancel",
            transitionSchema());

    /** Shared immutable definitions; exposing a schema never grants schedule authority. */
    public static Map<String, Object> inputSchema(String name) {
        return Objects.requireNonNull(INPUT_SCHEMAS.get(name), "Unknown tool schema: " + name);
    }

    private static Map<String, Object> transitionSchema() {
        return object(
                Map.of("id", string(128), "expected_revision", integer(1, Long.MAX_VALUE - 1)),
                List.of("id", "expected_revision"));
    }

    public static ToolRegistry register(ToolRegistry registry, ScheduleService service) {
        Objects.requireNonNull(service);
        registry.register(
                definition(
                        "schedule_execution",
                        SystemPrompts.SCHEDULE_EXECUTION_DESCRIPTION,
                        inputSchema("schedule_execution"),
                        true,
                        (context, input) -> service.create(context, ScheduleRecord.Action.execution, input)));
        registry.register(
                definition(
                        "schedule_message",
                        SystemPrompts.SCHEDULE_MESSAGE_DESCRIPTION,
                        inputSchema("schedule_message"),
                        true,
                        (context, input) -> service.create(context, ScheduleRecord.Action.message, input)));
        registry.register(
                definition(
                        "schedule_list",
                        SystemPrompts.SCHEDULE_LIST_DESCRIPTION,
                        inputSchema("schedule_list"),
                        false,
                        (context, input) -> Map
                                .of("status", "ok", "schedules", service.list(context.scope()))));
        registry.register(
                definition(
                        "schedule_get",
                        SystemPrompts.SCHEDULE_GET_DESCRIPTION,
                        inputSchema("schedule_get"),
                        false,
                        (context, input) -> service.get(context.scope(), (String) input.get("id"))));
        registry.register(
                definition(
                        "schedule_update",
                        SystemPrompts.SCHEDULE_UPDATE_DESCRIPTION,
                        inputSchema("schedule_update"),
                        true,
                        (context, input) -> service.update(context.scope(), input)));
        registerTransition(
                registry,
                service,
                "schedule_pause",
                ScheduleRecord.Status.paused,
                SystemPrompts.SCHEDULE_PAUSE_DESCRIPTION);
        registerTransition(
                registry,
                service,
                "schedule_resume",
                ScheduleRecord.Status.active,
                SystemPrompts.SCHEDULE_RESUME_DESCRIPTION);
        registerTransition(
                registry,
                service,
                "schedule_cancel",
                ScheduleRecord.Status.cancelled,
                SystemPrompts.SCHEDULE_CANCEL_DESCRIPTION);
        return registry;
    }

    private static void registerTransition(
            ToolRegistry registry,
            ScheduleService service,
            String name,
            ScheduleRecord.Status target,
            String description) {
        registry.register(
                definition(
                        name,
                        description,
                        inputSchema(name),
                        true,
                        (context, input) -> service.transition(
                                context.scope(),
                                (String) input.get("id"),
                                ((Number) input.get("expected_revision")).longValue(),
                                target)));
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
                (context, input) -> {
                    JsonSchema.validate(schema, input);
                    try {
                        return handler.execute(context, input);
                    } catch (NotFoundException missing) {
                        return error("not_found", missing);
                    } catch (ConflictException conflict) {
                        return error("revision_conflict", conflict);
                    } catch (SecurityException denied) {
                        return error("not_authorized", denied);
                    } catch (IllegalArgumentException invalid) {
                        return error("invalid_request", invalid);
                    } catch (ScheduleService.UnavailableException unavailable) {
                        return error("unavailable", unavailable);
                    }
                });
    }

    private static Map<String, Object> error(String status, RuntimeException failure) {
        return Map.of(
                "status",
                status,
                "message",
                failure.getMessage() == null ? status : failure.getMessage());
    }

    private static Map<String, Object> creationSchema(boolean message) {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("name", string(200));
        properties.put(message ? "message" : "prompt", string(message ? 40000 : 100000));
        properties.put("timing", timingSchema());
        properties.put("destination", destinationSchema());
        return object(
                properties,
                message
                        ? List.of("name", "message", "timing", "destination")
                        : List.of("name", "prompt", "timing"));
    }

    private static Map<String, Object> updateSchema() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("id", string(128));
        properties.put("expected_revision", integer(1, Long.MAX_VALUE - 1));
        properties.put("name", string(200));
        properties.put("prompt", string(100000));
        properties.put("message", string(40000));
        properties.put("timing", timingSchema());
        properties.put("destination", destinationSchema());
        properties.put("clear_destination", Map.of("type", "boolean"));
        return object(properties, List.of("id", "expected_revision"));
    }

    private static Map<String, Object> timingSchema() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put(
                "at",
                Map.of(
                        "type",
                        "string",
                        "minLength",
                        1,
                        "maxLength",
                        128,
                        "description",
                        SystemPrompts.SCHEDULE_AT_DESCRIPTION));
        properties.put(
                "delay_seconds",
                Map.of(
                        "type",
                        "integer",
                        "minimum",
                        1,
                        "maximum",
                        Long.MAX_VALUE,
                        "description",
                        SystemPrompts.SCHEDULE_DELAY_DESCRIPTION));
        properties.put(
                "cron",
                Map.of(
                        "type",
                        "string",
                        "minLength",
                        1,
                        "maxLength",
                        200,
                        "description",
                        SystemPrompts.SCHEDULE_CRON_DESCRIPTION));
        properties.put(
                "timezone",
                Map.of(
                        "type",
                        "string",
                        "minLength",
                        1,
                        "maxLength",
                        100,
                        "description",
                        SystemPrompts.SCHEDULE_TIMEZONE_DESCRIPTION));
        var schema = new LinkedHashMap<>(object(properties, List.of()));
        schema.put("description", SystemPrompts.SCHEDULE_TIMING_DESCRIPTION);
        return Map.copyOf(schema);
    }

    private static Map<String, Object> outputSchema() {
        var properties = new LinkedHashMap<String, Object>(metadataProperties());
        properties.put("message", text(40000));
        properties.put("current_revision", integer(1, Long.MAX_VALUE));
        properties.put(
                "schedules",
                Map.of(
                        "type",
                        "array",
                        "items",
                        object(metadataProperties(), List.of("id", "revision", "status"))));
        return object(properties, List.of("status"));
    }

    private static Map<String, Object> metadataProperties() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("id", string(128));
        properties.put("revision", integer(1, Long.MAX_VALUE));
        properties.put("status", string(64));
        properties.put("action_type", Map.of("type", "string", "enum", List.of("execution", "message")));
        properties.put("monitoring_job_id", string(128));
        properties.put("monitoring_revision", integer(1, Long.MAX_VALUE));
        properties.put("name", string(200));
        properties.put("prompt", string(100000));
        properties.put("message", string(40000));
        properties.put("timing", Map.of("type", "object", "additionalProperties", true));
        properties.put("timezone", string(100));
        properties.put("next_occurrence", text(128));
        properties.put("next_occurrence_local", string(200));
        properties.put("destination", destinationSchema());
        properties.put(
                "registration",
                Map.of("type", "string", "enum", List.of("registered", "pending", "unavailable")));
        properties.put("registration_failure", text(16000));
        properties.put(
                "missed_run_policy",
                Map.of("type", "string", "enum", List.of("skip", "run_once_after_recovery")));
        properties.put("overlap_policy", Map.of("type", "string", "enum", List.of("skip")));
        properties.put(
                "occurrences",
                Map.of("type", "array", "items", Map.of("type", "object", "additionalProperties", true)));
        return properties;
    }

}
