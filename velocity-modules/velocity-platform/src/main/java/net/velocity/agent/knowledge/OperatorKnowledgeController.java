package net.velocity.agent.knowledge;

import java.io.IOException;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import net.velocity.agent.CancellationHandle;
import net.velocity.agent.ToolRegistry;
import net.velocity.agent.http.LocalAccountScope;
import net.velocity.agent.http.OperatorDataRequests;

/** Protected fixed knowledge operations; the service owns scope, persistence and pagination. */
@RestController
@RequestMapping("/api/operator/knowledge")
public final class OperatorKnowledgeController {

    private final KnowledgeService knowledge;
    private final LocalAccountScope account;

    public OperatorKnowledgeController(KnowledgeService knowledge, LocalAccountScope account) {
        this.knowledge = knowledge;
        this.account = account;
    }

    @PostMapping(value = "/catalog", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> catalog(
            @RequestBody String body,
            @RequestParam Map<String, String> parameters) {
        OperatorDataRequests.noParameters(parameters);
        var request = OperatorDataRequests.read(body, schema("data_catalog"));
        return response(knowledge.catalog(context(), request));
    }

    @PostMapping(value = "/query", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> query(
            @RequestBody String body,
            @RequestParam Map<String, String> parameters) {
        OperatorDataRequests.noParameters(parameters);
        var request = OperatorDataRequests.read(body, schema("data_query"));
        return response(knowledge.query(context(), request));
    }

    @PostMapping(value = "/aggregate", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> aggregate(
            @RequestBody String body,
            @RequestParam Map<String, String> parameters) {
        OperatorDataRequests.noParameters(parameters);
        var request = OperatorDataRequests.read(body, schema("data_aggregate"));
        return response(knowledge.aggregate(context(), request));
    }

    @PostMapping(value = "/get", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> get(
            @RequestBody String body,
            @RequestParam Map<String, String> parameters) {
        OperatorDataRequests.noParameters(parameters);
        var request = OperatorDataRequests.read(body, schema("data_get"));
        return response(knowledge.get(context(), request));
    }

    @PostMapping(value = "/save", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> save(
            @RequestBody String body,
            @RequestParam Map<String, String> parameters) {
        OperatorDataRequests.noParameters(parameters);
        var request = OperatorDataRequests.read(body, schema("data_save"));
        return response(knowledge.save(context(), request));
    }

    @PostMapping(value = "/update", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> update(
            @RequestBody String body,
            @RequestParam Map<String, String> parameters) {
        OperatorDataRequests.noParameters(parameters);
        var request = OperatorDataRequests.read(body, schema("data_update"));
        return response(knowledge.update(context(), request));
    }

    @PostMapping(value = "/delete", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> delete(
            @RequestBody String body,
            @RequestParam Map<String, String> parameters) {
        OperatorDataRequests.noParameters(parameters);
        var request = OperatorDataRequests.read(body, schema("data_delete"));
        return response(knowledge.delete(context(), request));
    }

    private ToolRegistry.Context context() {
        // This marker distinguishes a direct operator write from an execution-owned tool call.
        return ToolRegistry.Context.builder()
                .executionId("local-operator")
                .scope(account.scope())
                .cancellation(new CancellationHandle())
                .build();
    }

    private Map<String, Object> schema(String name) {
        return KnowledgeTools.inputSchema(name);
    }

    private static ResponseEntity<Map<String, Object>> response(Map<String, Object> result) {
        HttpStatus status = switch (String.valueOf(result.get("status"))) {
            case "revision_conflict" -> HttpStatus.CONFLICT;
            case "not_found" -> HttpStatus.NOT_FOUND;
            case "invalid_request" -> HttpStatus.BAD_REQUEST;
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
                        "message",
                        "Invalid knowledge request: "
                                + (failure.getMessage() == null
                                        ? "check the request fields"
                                        : failure.getMessage())));
    }

    @ExceptionHandler({IOException.class, RuntimeException.class})
    ResponseEntity<Map<String, Object>> unavailable(Exception failure) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                Map.of(
                        "status",
                        "unavailable",
                        "message",
                        "Knowledge storage is unavailable. If a save was submitted, inspect the record before trying again."));
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    ResponseEntity<Map<String, Object>> accountUnavailable(
            org.springframework.web.server.ResponseStatusException failure) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                Map.of(
                        "status",
                        "unavailable",
                        "message",
                        "The local UI's linked Slack account is unavailable"));
    }
}
