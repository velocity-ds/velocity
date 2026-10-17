package net.velocity.agent.skills;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import net.velocity.agent.CancellationHandle;
import net.velocity.agent.ToolRegistry;
import net.velocity.agent.http.LocalAccountScope;
import net.velocity.agent.http.OperatorDataRequests;
import net.velocity.agent.tools.ToolSchemas;

@RestController
@RequestMapping("/api/operator/skills")
public final class OperatorSkillsController {

    private final SkillService skills;
    private final BundledSkills bundles;
    private final LocalAccountScope localAccount;
    private final Map<String, Map<String, Object>> schemas;
    private final Map<String, Object> bundleId;

    public OperatorSkillsController(
            SkillService skills,
            LocalAccountScope localAccount,
            ObjectProvider<BundledSkillSource> sources) {
        this.skills = skills;
        schemas = SkillTools.inputSchemas(skills.limits());
        bundles = new BundledSkills(skills, sources.orderedStream().toList());
        // An empty catalog accepts any well-formed id and installation then reports an unknown bundle.
        bundleId = bundles.ids().isEmpty()
                ? ToolSchemas.string(32)
                : ToolSchemas.enumeration(bundles.ids().toArray(String[]::new));
        this.localAccount = localAccount;
    }

    @PostMapping(value = "/bundles", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> bundles(
            @RequestBody String body,
            @RequestParam Map<String, String> parameters) {
        OperatorDataRequests.read(body, parameters, ToolSchemas.object(Map.of()));
        return response(bundles.list(context(null)));
    }

    @PostMapping(value = "/install", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> install(
            @RequestBody String body,
            @RequestParam Map<String, String> parameters) {
        var request = OperatorDataRequests.read(
                body,
                parameters,
                withRequestId(
                        ToolSchemas.object(
                                Map.of("bundle_id", bundleId),
                                "bundle_id")));
        return response(
                bundles.install(
                        context((String) request.get("request_id")),
                        (String) request.get("bundle_id")));
    }

    @PostMapping(value = "/list", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> list(
            @RequestBody String body,
            @RequestParam Map<String, String> parameters) {
        OperatorDataRequests.noParameters(parameters);
        return response(skills.list(context(null), OperatorDataRequests.read(body, schema("skill_list"))));
    }

    @PostMapping(value = "/load", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> load(
            @RequestBody String body,
            @RequestParam Map<String, String> parameters) {
        OperatorDataRequests.noParameters(parameters);
        return response(skills.load(context(null), OperatorDataRequests.read(body, schema("skill_load"))));
    }

    @PostMapping(value = "/reference", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> reference(
            @RequestBody String body,
            @RequestParam Map<String, String> parameters) {
        OperatorDataRequests.noParameters(parameters);
        return response(
                skills.reference(context(null), OperatorDataRequests.read(body, schema("skill_reference"))));
    }

    @PostMapping(value = "/create", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> create(
            @RequestBody String body,
            @RequestParam Map<String, String> parameters) {
        return write(body, parameters, "create");
    }

    @PostMapping(value = "/patch", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> patch(
            @RequestBody String body,
            @RequestParam Map<String, String> parameters) {
        return write(body, parameters, "patch");
    }

    private ResponseEntity<Map<String, Object>> write(
            String body,
            Map<String, String> parameters,
            String action) {
        OperatorDataRequests.noParameters(parameters);
        var request = OperatorDataRequests.read(body, withRequestId(schema("skill_" + action)));
        var input = new LinkedHashMap<>(request);
        input.remove("request_id");
        return response(
                action.equals("create")
                        ? skills.create(context((String) request.get("request_id")), input)
                        : skills.patch(context((String) request.get("request_id")), input));
    }

    @PostMapping(value = "/control", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> control(
            @RequestBody String body,
            @RequestParam Map<String, String> parameters) {
        OperatorDataRequests.noParameters(parameters);
        var request = OperatorDataRequests.read(
                body,
                withRequestId(
                        ToolSchemas.object(
                                Map.of(
                                        "id",
                                        ToolSchemas.string(64),
                                        "expected_revision",
                                        ToolSchemas.integer(1, Long.MAX_VALUE - 1),
                                        "enabled",
                                        Map.of("type", "boolean"),
                                        "pinned",
                                        Map.of("type", "boolean"),
                                        "managed_by_agent",
                                        Map.of("type", "boolean")),
                                List.of("id", "expected_revision"))));
        if (!request.containsKey("enabled") && !request.containsKey("pinned")
                && !request.containsKey("managed_by_agent")) {
            throw new IllegalArgumentException("Choose a skill control");
        }
        var input = new LinkedHashMap<>(request);
        input.remove("request_id");
        return response(skills.control(context((String) request.get("request_id")), input));
    }

    /** Apply (disable the skill) or reject a curator's retirement proposal. */
    @PostMapping(value = "/proposal", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> proposal(
            @RequestBody String body,
            @RequestParam Map<String, String> parameters) {
        OperatorDataRequests.noParameters(parameters);
        var request = OperatorDataRequests.read(
                body,
                withRequestId(
                        ToolSchemas.object(
                                Map.of(
                                        "proposal_id",
                                        ToolSchemas.string(64),
                                        "apply",
                                        Map.of("type", "boolean")),
                                List.of("proposal_id", "apply"))));
        var input = new LinkedHashMap<>(request);
        input.remove("request_id");
        return response(skills.proposalDecision(context((String) request.get("request_id")), input));
    }

    private ToolRegistry.Context context(String requestId) {
        if (requestId == null) {
            return ToolRegistry.Context.builder()
                    .executionId("local-operator")
                    .scope(localAccount.scope())
                    .cancellation(new CancellationHandle())
                    .build();
        }
        if (!UUID.fromString(requestId).toString().equals(requestId)) {
            throw new IllegalArgumentException("Invalid request ID");
        }
        return ToolRegistry.Context.builder()
                .executionId("local-operator")
                .scope(localAccount.scope())
                .cancellation(new CancellationHandle())
                .acceptedCall(requestId, "operator:" + requestId)
                .build();
    }

    private Map<String, Object> schema(String name) {
        return java.util.Objects.requireNonNull(schemas.get(name), "Unknown skill schema: " + name);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> withRequestId(Map<String, Object> schema) {
        var fields = new LinkedHashMap<>((Map<String, Object>) schema.get("properties"));
        fields.put("request_id", ToolSchemas.string(36));
        var required = new java.util.ArrayList<>((List<String>) schema.get("required"));
        required.add("request_id");
        return ToolSchemas.object(fields, required);
    }

    private static ResponseEntity<Map<String, Object>> response(Map<String, Object> body) {
        int code = switch (String.valueOf(body.get("status"))) {
            case "not_found", "version_not_found" -> 404;
            case "revision_conflict", "capacity_exceeded", "fresh_read_required", "already_proposed" -> 409;
            case "invalid_request" -> 400;
            case "protected", "disabled" -> 403;
            default -> 200;
        };
        return ResponseEntity.status(code).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException failure) {
        return ResponseEntity.badRequest().body(
                Map.of(
                        "status",
                        "invalid_request",
                        "message",
                        "Invalid skill request; check fields, limits and content."));
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<Map<String, Object>> unavailable(RuntimeException failure) {
        return ResponseEntity.status(503).body(
                Map.of(
                        "status",
                        "unavailable",
                        "message",
                        "Skill storage is unavailable. Inspect the current revision before another write."));
    }
}
