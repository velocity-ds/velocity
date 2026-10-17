package net.velocity.agent.learning;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import net.velocity.agent.AgentGateway;
import net.velocity.agent.http.LocalAccountScope;
import net.velocity.agent.http.OperatorDataRequests;
import net.velocity.agent.persistence.ConflictException;
import net.velocity.agent.persistence.NotFoundException;
import net.velocity.agent.state.ExecutionSnapshot;
import net.velocity.agent.state.ExecutionStatus;
import net.velocity.agent.tools.ToolSchemas;

/** A displayed response needs explicit acknowledgement; reads and polling have no learning side effect. */
@RestController
@RequestMapping("/api/operator")
public final class OperatorLearningController {

    private final AgentGateway gateway;
    private final LearningService learning;
    private final LearningProperties properties;
    private final LocalAccountScope localAccount;

    public OperatorLearningController(
            AgentGateway gateway,
            LearningService learning,
            LearningProperties properties,
            LocalAccountScope localAccount) {
        this.gateway = gateway;
        this.learning = learning;
        this.properties = properties;
        this.localAccount = localAccount;
    }

    @GetMapping("/tasks/{id}/delivery")
    Map<String, Object> delivery(@PathVariable String id, @RequestParam Map<String, String> parameters) {
        OperatorDataRequests.noParameters(parameters);
        var snapshot = owned(id);
        return Map.of(
                "execution_revision",
                snapshot.revision,
                "delivery_turn",
                snapshot.deliveryTurn,
                "response_digest",
                LearningService.responseDigest(snapshot),
                "learning_enabled",
                properties.enabled());
    }

    @PostMapping(value = "/tasks/{id}/acknowledge", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> acknowledge(
            @PathVariable String id,
            @RequestBody String body,
            @RequestParam Map<String, String> parameters) {
        OperatorDataRequests.noParameters(parameters);
        var input = OperatorDataRequests.read(
                body,
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of(
                                "delivery_turn",
                                ToolSchemas.integer(1, Long.MAX_VALUE),
                                "response_digest",
                                Map.of("type", "string", "minLength", 64, "maxLength", 64)),
                        "required",
                        List.of("delivery_turn", "response_digest"),
                        "additionalProperties",
                        false));
        long turn = ((Number) input.get("delivery_turn")).longValue();
        String digest = (String) input.get("response_digest");
        var snapshot = owned(id);
        if (snapshot.status != ExecutionStatus.succeeded || snapshot.cancellationRequested
                || !gateway.settled(id)
                || turn != snapshot.deliveryTurn
                || !LearningService.responseDigest(snapshot).equals(digest)) {
            throw new ConflictException("Displayed response is no longer eligible");
        }
        // This acknowledges the response displayed locally; it never advances the Slack delivery journal.
        return learning.acknowledge(snapshot, turn, digest);
    }

    /** Queues a user-requested skill review of a settled task; the result is a user-owned skill. */
    @PostMapping(value = "/tasks/{id}/learn", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> learn(
            @PathVariable String id,
            @RequestBody String body,
            @RequestParam Map<String, String> parameters) {
        OperatorDataRequests.noParameters(parameters);
        var input = OperatorDataRequests.read(
                body,
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of(
                                "name",
                                Map.of("type", "string", "minLength", 1, "maxLength", 100),
                                "focus",
                                Map.of("type", "string", "minLength", 1, "maxLength", 300)),
                        "required",
                        List.of(),
                        "additionalProperties",
                        false));
        var snapshot = owned(id);
        if (snapshot.status != ExecutionStatus.succeeded || snapshot.cancellationRequested
                || !gateway.settled(id)) {
            throw new ConflictException("Task is not a settled successful execution");
        }
        var focus = new StringBuilder();
        if (input.get("name") instanceof String name) {
            focus.append("Skill name: ").append(name.strip()).append('.');
        }
        if (input.get("focus") instanceof String guidance) {
            focus.append(focus.isEmpty() ? "" : " ").append(guidance.strip());
        }
        var result = learning
                .learn(localAccount.scope(), snapshot, focus.isEmpty() ? null : focus.toString());
        int code = switch (String.valueOf(result.get("status"))) {
            case "queued" -> 200;
            case "disabled", "skills_disabled" -> 403;
            default -> 409;
        };
        return ResponseEntity.status(code).body(result);
    }

    @GetMapping("/learning")
    Map<String, Object> status(@RequestParam Map<String, String> parameters) {
        OperatorDataRequests.noParameters(parameters);
        return learning.inspect(localAccount.scope());
    }

    @GetMapping("/learning/journey")
    Map<String, Object> journey(@RequestParam Map<String, String> parameters) {
        OperatorDataRequests.noParameters(parameters);
        return learning.journey(localAccount.scope());
    }

    private ExecutionSnapshot owned(String id) {
        var operator = localAccount.scope();
        var snapshot = gateway.get(id);
        if (!operator.sameOwner(snapshot.scope)) {
            throw new NotFoundException("Account task not found");
        }
        return snapshot;
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Map<String, String>> missing() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("status", "not_found"));
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<Map<String, String>> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("status", "revision_conflict"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid() {
        return ResponseEntity.badRequest().body(Map.of("status", "invalid_request"));
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<Map<String, String>> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("status", "unavailable"));
    }
}
