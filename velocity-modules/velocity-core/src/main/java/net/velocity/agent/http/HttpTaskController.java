package net.velocity.agent.http;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import net.velocity.agent.AgentGateway;
import net.velocity.agent.ModelClient;
import net.velocity.agent.persistence.ConflictException;
import net.velocity.agent.persistence.NotFoundException;
import net.velocity.agent.state.ExecutionSnapshot;
import net.velocity.agent.state.StartRequest;
import net.velocity.agent.state.TrustedScope;

/** Thin trusted local adapter. The gateway owns all transitions and worker dispatch. */
@RestController
public final class HttpTaskController {

    private final AgentGateway gateway;
    private final ModelClient model;
    private final LocalAccountScope account;
    private final TrustedScope operator;

    public HttpTaskController(AgentGateway gateway, ModelClient model, LocalAccountScope account) {
        this.gateway = gateway;
        this.model = model;
        this.account = account;
        this.operator = new TrustedScope(
                "local",
                "operator",
                "operator",
                "velocity",
                "local");
    }

    @PostMapping(value = "/api/agent/executions", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ExecutionSnapshot> create(@RequestBody String body) {
        var request = TaskRequests.create(body);
        var scope = account.scope();
        if (!model.available()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Inference is not configured");
        }
        return ResponseEntity.accepted().body(
                gateway.create(
                        new StartRequest(
                                request.request(),
                                request.conversationId(),
                                scope,
                                null,
                                List.of(),
                                List.of(),
                                null,
                                null,
                                null,
                                List.of(),
                                null,
                                request.files())));
    }

    @GetMapping("/api/agent/executions")
    List<ExecutionSnapshot> list() {
        return gateway.list();
    }

    @GetMapping("/api/agent/executions/{id}")
    ExecutionSnapshot get(@PathVariable String id) {
        return gateway.get(id);
    }

    @PostMapping(value = "/api/agent/executions/{id}/updates", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ExecutionSnapshot> update(@PathVariable String id, @RequestBody String body) {
        var request = TaskRequests.update(body);
        return ResponseEntity.accepted().body(gateway.steer(id, request.text(), account.scope()));
    }

    @PostMapping(value = "/api/agent/executions/{id}/answers", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ExecutionSnapshot> answer(@PathVariable String id, @RequestBody String body) {
        var request = TaskRequests.answer(body);
        return ResponseEntity.accepted()
                .body(gateway.answer(id, request.answers(), request.expectedRevision(), account.scope()));
    }

    @PostMapping(value = "/api/agent/executions/{id}/approvals", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ExecutionSnapshot> approve(@PathVariable String id, @RequestBody String body) {
        var request = TaskRequests.approval(body);
        return ResponseEntity.accepted().body(
                gateway.approve(
                        id,
                        request.callId(),
                        request.approved(),
                        request.expectedRevision(),
                        account.scope(),
                        null));
    }

    @PostMapping(value = "/api/agent/executions/{id}/continue", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ExecutionSnapshot> continueTask(@PathVariable String id, @RequestBody String body) {
        var request = TaskRequests.continuation(body);
        if (!model.available()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Inference is not configured");
        }
        return ResponseEntity.accepted().body(gateway.continueTask(id, request.text(), account.scope()));
    }

    @PostMapping("/api/agent/executions/{id}/cancel")
    ResponseEntity<ExecutionSnapshot> cancel(@PathVariable String id) {
        return ResponseEntity.accepted().body(gateway.cancel(id, operator, true));
    }

    @GetMapping(value = "/api/agent/executions/{id}/artifacts/{artifact}", produces = MediaType.TEXT_PLAIN_VALUE)
    ResponseEntity<String> artifact(
            @PathVariable String id,
            @PathVariable String artifact,
            @RequestParam(defaultValue = "0") long offset,
            @RequestParam(defaultValue = "24576") int limit) {
        if (offset < 0 || limit < 1 || limit > 65_536) {
            throw new IllegalArgumentException("Artifact read must use offset >= 0 and limit 1..65536");
        }
        return ResponseEntity.ok().header("X-Content-Type-Options", "nosniff")
                .body(gateway.artifact(id, artifact, offset, limit));
    }

    @DeleteMapping(value = "/api/operator/tasks/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> delete(@PathVariable String id, @RequestBody String body) {
        gateway.delete(id, Instant.parse(TaskRequests.delete(body).expectedUpdatedAt()));
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Map<String, String>> missing(NotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "Execution or artifact not found");
    }

    @ExceptionHandler(net.velocity.agent.AdmissionCapacityException.class)
    ResponseEntity<Map<String, String>> capacity(
            net.velocity.agent.AdmissionCapacityException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "1")
                .body(
                        Map.of(
                                "error",
                                "agent_queue_full",
                                "message",
                                "Installation task queue is full; retry later"));
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<Map<String, String>> conflict(ConflictException exception) {
        return error(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<Map<String, String>> forbidden(SecurityException exception) {
        return error(HttpStatus.FORBIDDEN, "Linked account does not own this task");
    }

    @ExceptionHandler({IllegalArgumentException.class, java.time.format.DateTimeParseException.class})
    ResponseEntity<Map<String, String>> invalid(RuntimeException exception) {
        return error(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .body(Map.of("error", message == null ? status.getReasonPhrase() : message));
    }
}
