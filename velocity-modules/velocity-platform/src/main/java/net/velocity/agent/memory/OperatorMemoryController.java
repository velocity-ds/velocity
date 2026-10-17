package net.velocity.agent.memory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import net.velocity.agent.CancellationHandle;
import net.velocity.agent.ToolRegistry;
import net.velocity.agent.http.LocalAccountScope;
import net.velocity.agent.http.OperatorDataRequests;
import net.velocity.agent.tools.ToolSchemas;

/** Linked-account local controls. No body field can select an owner or approve a review as a model tool. */
@RestController
@RequestMapping("/api/operator/memory")
public final class OperatorMemoryController {

    private final MemoryService service;
    private final LocalAccountScope localAccount;

    public OperatorMemoryController(MemoryService service, LocalAccountScope localAccount) {
        this.service = service;
        this.localAccount = localAccount;
    }

    @PostMapping(value = "/inspect", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> inspect(
            @RequestBody String body,
            @RequestParam Map<String, String> parameters) {
        return response(
                service.inspect(
                        context(),
                        OperatorDataRequests.read(
                                body,
                                parameters,
                                ToolSchemas.object(Map.of("target", MemoryTools.target()), List.of()))));
    }

    @PostMapping(value = "/update", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> update(
            @RequestBody String body,
            @RequestParam Map<String, String> parameters) {
        @SuppressWarnings("unchecked")
        var fields = new LinkedHashMap<>((Map<String, Object>) MemoryTools.updateSchema().get("properties"));
        fields.remove("source_message_id");
        fields.remove("source_execution_id");
        fields.put("request_id", ToolSchemas.string(128));
        return response(
                service.operatorUpdate(
                        context(),
                        OperatorDataRequests.read(
                                body,
                                parameters,
                                ToolSchemas.object(
                                        fields,
                                        List.of(
                                                "request_id",
                                                "target",
                                                "expected_revision",
                                                "operations")))));
    }

    @PostMapping(value = "/control", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> control(
            @RequestBody String body,
            @RequestParam Map<String, String> parameters) {
        Map<String, Object> fields = Map.of(
                "request_id",
                ToolSchemas.string(128),
                "target",
                MemoryTools.target(),
                "expected_revision",
                ToolSchemas.integer(0, Long.MAX_VALUE - 1),
                "enabled",
                Map.<String, Object>of("type", "boolean"));
        return response(
                service.control(
                        context(),
                        OperatorDataRequests.read(
                                body,
                                parameters,
                                ToolSchemas.object(fields, List.copyOf(fields.keySet())))));
    }

    @PostMapping(value = "/proposal", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> proposal(
            @RequestBody String body,
            @RequestParam Map<String, String> parameters) {
        Map<String, Object> fields = Map.of(
                "request_id",
                ToolSchemas.string(128),
                "target",
                MemoryTools.target(),
                "proposal_id",
                ToolSchemas.string(64),
                "apply",
                Map.<String, Object>of("type", "boolean"));
        return response(
                service.proposalDecision(
                        context(),
                        OperatorDataRequests.read(
                                body,
                                parameters,
                                ToolSchemas.object(fields, List.copyOf(fields.keySet())))));
    }

    private ToolRegistry.Context context() {
        return ToolRegistry.Context.builder()
                .executionId("local-operator")
                .scope(localAccount.scope())
                .cancellation(new CancellationHandle())
                .build();
    }

    private static ResponseEntity<Map<String, Object>> response(Map<String, Object> result) {
        HttpStatus status = switch (String.valueOf(result.get("status"))) {
            case "revision_conflict", "capacity_exceeded", "receipt_capacity", "write_limit" ->
                HttpStatus.CONFLICT;
            case "invalid_request" -> HttpStatus.BAD_REQUEST;
            case "forbidden", "disabled" -> HttpStatus.FORBIDDEN;
            case "not_found" -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.OK;
        };
        return ResponseEntity.status(status).body(result);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException failure) {
        return ResponseEntity.badRequest().body(
                Map.of(
                        "status",
                        "invalid_request",
                        "database_committed",
                        false,
                        "message",
                        "Invalid memory request; check the typed fields and limits"));
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<Map<String, Object>> unavailable(RuntimeException failure) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                Map.of(
                        "status",
                        "unavailable",
                        "database_committed",
                        false,
                        "message",
                        "Memory storage is unavailable; inspect the exact request receipt before retrying a write"));
    }
}
