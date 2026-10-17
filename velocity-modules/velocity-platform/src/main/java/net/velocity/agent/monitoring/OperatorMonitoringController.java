package net.velocity.agent.monitoring;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import net.velocity.agent.CancellationHandle;
import net.velocity.agent.JsonSchema;
import net.velocity.agent.ToolRegistry;
import net.velocity.agent.http.LocalAccountScope;
import net.velocity.agent.http.OperatorDataRequests;
import net.velocity.agent.persistence.ConflictException;
import net.velocity.agent.persistence.NotFoundException;

/** Fixed local scope and schemas; external destinations cannot be manufactured through this adapter. */
@RestController
@RequestMapping("/api/operator/monitoring")
public final class OperatorMonitoringController {

    private static final Set<String> DEFINITION_FIELDS = Set.of(
            "name",
            "original_request",
            "objective",
            "criteria",
            "exclusions",
            "sources",
            "timing",
            "notification_policy",
            "digest_timing",
            "notification_instructions",
            "initial_summary",
            "end_at",
            "completion_condition",
            "completion_rule",
            "clear_completion_rule",
            "expected_revision");
    private final MonitoringService monitoring;
    private final LocalAccountScope localAccount;
    private final Map<String, Object> createSchema = OperatorDataRequests
            .fields(MonitoringTools.creationSchema(), DEFINITION_FIELDS);
    private final Map<String, Object> updateSchema = OperatorDataRequests
            .fields(MonitoringTools.updateSchema(), DEFINITION_FIELDS);
    private final Map<String, Object> transitionSchema = OperatorDataRequests
            .fields(MonitoringTools.controlSchema(), Set.of("expected_revision"));

    public OperatorMonitoringController(MonitoringService monitoring, LocalAccountScope localAccount) {
        this.monitoring = monitoring;
        this.localAccount = localAccount;
    }

    @GetMapping
    Map<String, Object> list(
            @RequestBody(required = false) String body,
            @RequestParam Map<String, String> parameters) {
        var page = page(body, parameters);
        return monitoring.list(localAccount.scope(), page[0], page[1]);
    }

    @GetMapping("/{id}")
    Map<String, Object> get(
            @PathVariable String id,
            @RequestBody(required = false) String body,
            @RequestParam Map<String, String> parameters) {
        OperatorDataRequests.noBody(body, parameters);
        validateId(id);
        return monitoring.get(localAccount.scope(), id);
    }

    @GetMapping({"/{id}/runs", "/{id}/observations", "/{id}/updates"})
    Map<String, Object> history(
            @PathVariable String id,
            @RequestBody(required = false) String body,
            @RequestParam Map<String, String> parameters,
            jakarta.servlet.http.HttpServletRequest request) {
        validateId(id);
        var page = page(body, parameters);
        String path = request.getRequestURI();
        return monitoring.history(
                localAccount.scope(),
                id,
                path.substring(path.lastIndexOf('/') + 1),
                page[0],
                page[1]);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> create(
            @RequestBody String body,
            @RequestParam Map<String, String> parameters) {
        OperatorDataRequests.noParameters(parameters);
        var input = OperatorDataRequests.read(body, createSchema);
        return ResponseEntity.status(HttpStatus.CREATED).body(
                monitoring.create(
                        ToolRegistry.Context.builder()
                                .executionId("local-operator")
                                .scope(localAccount.scope())
                                .cancellation(new CancellationHandle())
                                .build(),
                        input));
    }

    @PatchMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> update(
            @PathVariable String id,
            @RequestBody String body,
            @RequestParam Map<String, String> parameters) {
        OperatorDataRequests.noParameters(parameters);
        validateId(id);
        var input = new LinkedHashMap<>(OperatorDataRequests.read(body, updateSchema));
        input.put("id", id);
        return monitoring.update(localAccount.scope(), input);
    }

    @PostMapping(value = "/{id}/pause", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> pause(
            @PathVariable String id,
            @RequestBody String body,
            @RequestParam Map<String, String> parameters) {
        return transition(id, body, parameters, MonitoringJob.Status.PAUSED);
    }

    @PostMapping(value = "/{id}/resume", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> resume(
            @PathVariable String id,
            @RequestBody String body,
            @RequestParam Map<String, String> parameters) {
        return transition(id, body, parameters, MonitoringJob.Status.ACTIVE);
    }

    @PostMapping(value = "/{id}/close", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> close(
            @PathVariable String id,
            @RequestBody String body,
            @RequestParam Map<String, String> parameters) {
        return transition(id, body, parameters, MonitoringJob.Status.CLOSED);
    }

    @PostMapping(value = "/{id}/run-now", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> runNow(
            @PathVariable String id,
            @RequestBody String body,
            @RequestParam Map<String, String> parameters) {
        var request = control(id, body, parameters);
        return monitoring.runNow(localAccount.scope(), id, OperatorDataRequests.revision(request));
    }

    private Map<String, Object> transition(
            String id,
            String body,
            Map<String, String> parameters,
            MonitoringJob.Status status) {
        var request = control(id, body, parameters);
        return monitoring
                .transition(localAccount.scope(), id, OperatorDataRequests.revision(request), status);
    }

    private Map<String, Object> control(String id, String body, Map<String, String> parameters) {
        OperatorDataRequests.noParameters(parameters);
        validateId(id);
        return OperatorDataRequests.read(body, transitionSchema);
    }

    private static void validateId(String id) {
        JsonSchema.validate(Map.of("type", "string", "minLength", 1, "maxLength", 128), id);
    }

    private static int[] page(String body, Map<String, String> parameters) {
        if (body != null && !body.isBlank()) {
            throw new IllegalArgumentException("This route accepts no request body");
        }
        if (!Set.of("offset", "limit").containsAll(parameters.keySet())) {
            throw new IllegalArgumentException("Only offset and limit are accepted");
        }
        try {
            int offset = Integer.parseInt(parameters.getOrDefault("offset", "0"));
            int limit = Integer.parseInt(parameters.getOrDefault("limit", "20"));
            if (offset < 0 || limit < 1 || limit > 100) {
                throw new IllegalArgumentException("Invalid pagination bounds");
            }
            return new int[]{offset, limit};
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Pagination requires whole numbers");
        }
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Map<String, Object>> missing(NotFoundException failure) {
        return error(HttpStatus.NOT_FOUND, "not_found", "Monitoring job not found");
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<Map<String, Object>> conflict(ConflictException failure) {
        return error(HttpStatus.CONFLICT, "revision_conflict", failure.getMessage());
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<Map<String, Object>> forbidden(SecurityException failure) {
        return error(
                HttpStatus.FORBIDDEN,
                "not_authorized",
                "The local operator does not have authority for this monitoring action");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException failure) {
        return error(HttpStatus.BAD_REQUEST, "invalid_request", failure.getMessage());
    }

    @ExceptionHandler(MonitoringService.UnavailableException.class)
    ResponseEntity<Map<String, Object>> disabled(MonitoringService.UnavailableException failure) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "unavailable", failure.getMessage());
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, Object>> missingAccount(ResponseStatusException failure) {
        return error(
                HttpStatus.SERVICE_UNAVAILABLE,
                "unavailable",
                "The local UI's linked Slack account is unavailable");
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<Map<String, Object>> unavailable(RuntimeException failure) {
        return error(
                HttpStatus.SERVICE_UNAVAILABLE,
                "unavailable",
                "Monitoring storage is unavailable. Inspect the saved job before repeating a submitted change.");
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus http, String status, String message) {
        return ResponseEntity.status(http)
                .body(Map.of("status", status, "message", message == null ? status : message));
    }
}
