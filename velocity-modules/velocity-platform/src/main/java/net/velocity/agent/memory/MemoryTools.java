package net.velocity.agent.memory;

import java.util.List;
import java.util.Map;

import net.velocity.agent.JsonSchema;
import net.velocity.agent.ToolRegistry;
import net.velocity.agent.tools.ToolSchemas;
import net.velocity.inference.SystemPrompts;

import static net.velocity.agent.tools.ToolSchemas.*;

public final class MemoryTools {

    private MemoryTools() {
    }

    public static ToolRegistry register(ToolRegistry registry, MemoryService service) {
        registry.register(
                definition(
                        "memory_inspect",
                        SystemPrompts.MEMORY_INSPECT_DESCRIPTION,
                        object(Map.of("target", target()), List.of()),
                        false,
                        service::inspect));
        registry.register(
                definition(
                        "memory_update",
                        SystemPrompts.MEMORY_UPDATE_DESCRIPTION,
                        updateSchema(),
                        true,
                        new ToolRegistry.ReconcilingHandler() {

                            @Override
                            public Object execute(ToolRegistry.Context context, Map<String, Object> input) {
                                JsonSchema.validate(updateSchema(), input);
                                return service.update(context, input);
                            }

                            @Override
                            public java.util.Optional<Object> reconcile(
                                    ToolRegistry.Context context,
                                    Map<String, Object> input) {
                                JsonSchema.validate(updateSchema(), input);
                                return service.reconcile(context, input);
                            }
                        }));
        registry.register(
                definition(
                        "memory_control",
                        SystemPrompts.MEMORY_CONTROL_DESCRIPTION,
                        object(
                                Map.of(
                                        "target",
                                        target(),
                                        "expected_revision",
                                        integer(0, Long.MAX_VALUE - 1),
                                        "enabled",
                                        Map.of("type", "boolean")),
                                List.of("target", "expected_revision", "enabled")),
                        true,
                        control(service, "control")));
        registry.register(
                definition(
                        "memory_proposal",
                        SystemPrompts.MEMORY_PROPOSAL_DESCRIPTION,
                        object(
                                Map.of(
                                        "target",
                                        target(),
                                        "proposal_id",
                                        string(64),
                                        "apply",
                                        Map.of("type", "boolean")),
                                List.of("target", "proposal_id", "apply")),
                        true,
                        control(service, "proposal")));
        return registry;
    }

    private static ToolRegistry.ReconcilingHandler control(MemoryService service, String command) {
        return new ToolRegistry.ReconcilingHandler() {

            @Override
            public Object execute(ToolRegistry.Context context, Map<String, Object> input) {
                return command.equals("control")
                        ? service.foregroundControl(context, input)
                        : service.foregroundProposal(context, input);
            }

            @Override
            public java.util.Optional<Object> reconcile(
                    ToolRegistry.Context context,
                    Map<String, Object> input) {
                return service.reconcile(context, input, command);
            }
        };
    }

    public static Map<String, Object> updateSchema() {
        return object(
                Map.of(
                        "target",
                        target(),
                        "expected_revision",
                        integer(0, Long.MAX_VALUE - 1),
                        "operations",
                        Map.of(
                                "type",
                                "array",
                                "minItems",
                                1,
                                "maxItems",
                                20,
                                "items",
                                object(
                                        Map.of(
                                                "action",
                                                Map.of(
                                                        "type",
                                                        "string",
                                                        "enum",
                                                        List.of("add", "replace", "remove")),
                                                "entry_id",
                                                string(64),
                                                "content",
                                                string(Integer.MAX_VALUE)),
                                        List.of("action"))),
                        "source_message_id",
                        string(256),
                        "source_execution_id",
                        string(128)),
                List.of("target", "expected_revision", "operations"));
    }

    private static ToolRegistry.Definition definition(
            String name,
            String description,
            Map<String, Object> schema,
            boolean write,
            ToolRegistry.Handler handler) {
        // The handler is retained as ReconcilingHandler for the runner's explicit recovery path.
        return new ToolRegistry.Definition(
                name,
                description,
                schema,
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of("status", string(64), "database_committed", Map.of("type", "boolean")),
                        "required",
                        List.of("status", "database_committed"),
                        "additionalProperties",
                        true),
                write ? "repository.write" : "repository.read",
                true,
                true,
                false,
                10000,
                ToolRegistry.MonitoringAccess.denied,
                handler);
    }

    public static Map<String, Object> target() {
        return Map.of("type", "string", "enum", List.of("user", "environment", "profile"));
    }
}
