package net.velocity.agent.skills;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.velocity.agent.ToolRegistry;
import net.velocity.agent.tools.ToolSchemas;
import net.velocity.inference.SystemPrompts;

import static net.velocity.agent.tools.ToolSchemas.*;

/** Bounded schemas expose inert knowledge, never execution or protection controls. */
public final class SkillTools {

    private SkillTools() {
    }

    /** Immutable definitions bound only to trusted skill limits, with no execution handlers. */
    public static Map<String, Map<String, Object>> inputSchemas(SkillsProperties limits) {
        var schemas = new LinkedHashMap<String, Map<String, Object>>();
        schemas.put(
                "skill_list",
                object(
                        Map.of(
                                "search",
                                string(100),
                                "offset",
                                Map.of(
                                        "type",
                                        "integer",
                                        "minimum",
                                        0,
                                        "maximum",
                                        limits.maximumSkills()),
                                "limit",
                                Map.of(
                                        "type",
                                        "integer",
                                        "minimum",
                                        1,
                                        "maximum",
                                        limits.maximumSkills())),
                        List.of()));
        schemas.put(
                "skill_load",
                object(
                        Map.of("id", string(64), "revision", integer(1, Long.MAX_VALUE - 1)),
                        List.of("id")));
        schemas.put(
                "skill_reference",
                object(
                        Map.of(
                                "id",
                                string(64),
                                "revision",
                                integer(1, Long.MAX_VALUE - 1),
                                "reference_id",
                                string(64)),
                        List.of("id", "revision", "reference_id")));
        var fields = new LinkedHashMap<String, Object>();
        fields.put("name", string(100));
        fields.put("routing_description", string(300));
        fields.put("core_instructions", string(limits.maximumCoreChars()));
        fields.put(
                "references",
                Map.of(
                        "type",
                        "array",
                        "maxItems",
                        Integer.MAX_VALUE,
                        "items",
                        object(
                                Map.of(
                                        "id",
                                        string(64),
                                        "name",
                                        string(100),
                                        "content",
                                        string(limits.maximumReferenceChars())),
                                List.of("id", "name", "content"))));
        schemas.put(
                "skill_create",
                object(fields, List.of("name", "routing_description", "core_instructions")));
        fields.put("id", string(64));
        fields.put("expected_revision", integer(1, Long.MAX_VALUE - 1));
        schemas.put("skill_patch", object(fields, List.of("id", "expected_revision")));
        return Map.copyOf(schemas);
    }

    public static ToolRegistry register(ToolRegistry registry, SkillService skills) {
        var schemas = inputSchemas(skills.limits());
        registry.register(
                definition(
                        "skill_list",
                        SystemPrompts.SKILL_LIST_DESCRIPTION,
                        schemas.get("skill_list"),
                        false,
                        complete(skills::list)));
        registry.register(
                definition(
                        "skill_load",
                        SystemPrompts.SKILL_LOAD_DESCRIPTION,
                        schemas.get("skill_load"),
                        false,
                        complete(skills::load)));
        registry.register(
                definition(
                        "skill_reference",
                        SystemPrompts.SKILL_REFERENCE_DESCRIPTION,
                        schemas.get("skill_reference"),
                        false,
                        complete(skills::reference)));
        registry.register(
                definition(
                        "skill_create",
                        SystemPrompts.SKILL_CREATE_DESCRIPTION,
                        schemas.get("skill_create"),
                        true,
                        mutation(skills, "create")));
        registry.register(
                definition(
                        "skill_patch",
                        SystemPrompts.SKILL_PATCH_DESCRIPTION,
                        schemas.get("skill_patch"),
                        true,
                        mutation(skills, "patch")));
        return registry;
    }

    private static ToolRegistry.CompleteResultHandler complete(ToolRegistry.Handler handler) {
        return new ToolRegistry.CompleteResultHandler() {

            @Override
            public Object execute(ToolRegistry.Context context, Map<String, Object> input) throws Exception {
                return handler.execute(context, input);
            }
        };
    }

    private static ToolRegistry.ReconcilingHandler mutation(SkillService service, String action) {
        return new ToolRegistry.ReconcilingHandler() {

            @Override
            public Object execute(ToolRegistry.Context context, Map<String, Object> input) {
                try {
                    return action.equals("create")
                            ? service.create(context, input)
                            : service.patch(context, input);
                } catch (IllegalArgumentException invalid) {
                    return Map.of(
                            "status",
                            "invalid_request",
                            "database_committed",
                            false,
                            "message",
                            "Invalid skill input; check limits, logical IDs and protected content.");
                }
            }

            @Override
            public Optional<Object> reconcile(ToolRegistry.Context context, Map<String, Object> input) {
                return service.reconcile(context, input, action);
            }
        };
    }

    private static ToolRegistry.Definition definition(
            String name,
            String description,
            Map<String, Object> schema,
            boolean write,
            ToolRegistry.Handler handler) {
        // The runner validates input before dispatch. Keeping the reconciler as the actual handler
        // is necessary for trusted read-only recovery of a commit preceding runner settlement.
        return new ToolRegistry.Definition(
                name,
                description,
                schema,
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of("status", string(64)),
                        "required",
                        List.of("status"),
                        "additionalProperties",
                        true),
                write ? "repository.write" : "repository.read",
                true,
                true,
                false,
                0,
                ToolRegistry.MonitoringAccess.denied,
                handler);
    }
}
