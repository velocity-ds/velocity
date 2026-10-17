package net.velocity.agent.scheduling;

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

/** Local agent-prompt schedules; Slack destination authority cannot be manufactured by JSON. */
@RestController
@RequestMapping("/api/operator/schedules")
public final class OperatorScheduleController {

    private final ScheduleService schedules;
    private final LocalAccountScope localAccount;
    private final Map<String, Object> createSchema;
    private final Map<String, Object> updateSchema;
    private final Map<String, Object> transitionSchema;
    private final Map<String, Object> idSchema;

    public OperatorScheduleController(ScheduleService schedules, LocalAccountScope localAccount) {
        this.schedules = schedules;
        this.localAccount = localAccount;
        createSchema = OperatorDataRequests.fields(
                SchedulingTools.inputSchema("schedule_execution"),
                Set.of("name", "prompt", "timing"));
        updateSchema = OperatorDataRequests.fields(
                SchedulingTools.inputSchema("schedule_update"),
                Set.of("expected_revision", "name", "prompt", "timing"));
        transitionSchema = OperatorDataRequests.fields(
                SchedulingTools.inputSchema("schedule_pause"),
                Set.of("expected_revision"));
        idSchema = SchedulingTools.inputSchema("schedule_get");
    }

    @GetMapping
    Map<String, Object> list(
            @RequestBody(required = false) String body,
            @RequestParam Map<String, String> parameters) {
        OperatorDataRequests.noBody(body, parameters);
        return Map.of("status", "ok", "schedules", schedules.list(localAccount.scope()));
    }

    @GetMapping("/{id}")
    Map<String, Object> get(
            @PathVariable String id,
            @RequestBody(required = false) String body,
            @RequestParam Map<String, String> parameters) {
        OperatorDataRequests.noBody(body, parameters);
        validateId(id);
        return schedules.get(localAccount.scope(), id);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> create(
            @RequestBody String body,
            @RequestParam Map<String, String> parameters) {
        OperatorDataRequests.noParameters(parameters);
        var request = OperatorDataRequests.read(body, createSchema);
        var context = ToolRegistry.Context.builder()
                .executionId("local-operator")
                .scope(localAccount.scope())
                .cancellation(new CancellationHandle())
                .build();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(schedules.create(context, ScheduleRecord.Action.execution, request));
    }

    @PatchMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> update(
            @PathVariable String id,
            @RequestBody String body,
            @RequestParam Map<String, String> parameters) {
        OperatorDataRequests.noParameters(parameters);
        validateId(id);
        var request = OperatorDataRequests.read(body, updateSchema);
        var input = new LinkedHashMap<>(request);
        input.put("id", id);
        return schedules.update(localAccount.scope(), input);
    }

    @PostMapping(value = "/{id}/pause", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> pause(
            @PathVariable String id,
            @RequestBody String body,
            @RequestParam Map<String, String> parameters) {
        return transition(id, body, parameters, ScheduleRecord.Status.paused);
    }

    @PostMapping(value = "/{id}/resume", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> resume(
            @PathVariable String id,
            @RequestBody String body,
            @RequestParam Map<String, String> parameters) {
        return transition(id, body, parameters, ScheduleRecord.Status.active);
    }

    @PostMapping(value = "/{id}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> cancel(
            @PathVariable String id,
            @RequestBody String body,
            @RequestParam Map<String, String> parameters) {
        return transition(id, body, parameters, ScheduleRecord.Status.cancelled);
    }

    private Map<String, Object> transition(
            String id,
            String body,
            Map<String, String> parameters,
            ScheduleRecord.Status target) {
        OperatorDataRequests.noParameters(parameters);
        validateId(id);
        var request = OperatorDataRequests.read(body, transitionSchema);
        return schedules.transition(localAccount.scope(), id, OperatorDataRequests.revision(request), target);
    }

    private void validateId(String id) {
        JsonSchema.validate(idSchema, Map.of("id", id));
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Map<String, Object>> missing(NotFoundException failure) {
        return error(HttpStatus.NOT_FOUND, "not_found", "Schedule not found");
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
                "The local operator does not have authority for this schedule destination");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException failure) {
        return error(HttpStatus.BAD_REQUEST, "invalid_request", failure.getMessage());
    }

    @ExceptionHandler(ScheduleService.UnavailableException.class)
    ResponseEntity<Map<String, Object>> disabled(ScheduleService.UnavailableException failure) {
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
                "Schedule storage is unavailable. If a change was submitted, inspect the schedule before trying again.");
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus http, String status, String message) {
        return ResponseEntity.status(http)
                .body(Map.of("status", status, "message", message == null ? status : message));
    }
}
