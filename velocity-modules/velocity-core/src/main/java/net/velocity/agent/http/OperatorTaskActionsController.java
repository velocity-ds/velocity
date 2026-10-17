package net.velocity.agent.http;

import java.io.IOException;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import net.velocity.agent.AgentGateway;
import net.velocity.agent.persistence.NotFoundException;
import net.velocity.agent.state.AcceptedCall;
import net.velocity.agent.state.ExecutionStatus;

import jakarta.servlet.http.HttpServletRequest;

/** Advisory eligibility only. Mutation endpoints always recheck authority and lifecycle. */
@RestController
public final class OperatorTaskActionsController {

    private final AgentGateway gateway;
    private final LocalAccountScope account;

    public OperatorTaskActionsController(AgentGateway gateway, LocalAccountScope account) {
        this.gateway = gateway;
        this.account = account;
    }

    public record Actions(
            long revision,
            boolean owner,
            boolean settled,
            boolean steer,
            boolean answer,
            boolean approve,
            boolean cancel,
            boolean delete,
            boolean continueTask,
            String continuation) {}

    @GetMapping("/api/operator/tasks/{id}/actions")
    Actions actions(@PathVariable String id, HttpServletRequest request) throws IOException {
        if (request.getQueryString() != null || request.getInputStream().read() != -1) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Actions accept no body or query parameters");
        }
        var snapshot = gateway.get(id);
        boolean owner = account.scope().sameOwner(snapshot.scope);
        boolean settled = gateway.settled(id);
        boolean active = !snapshot.status.terminal() && !snapshot.cancellationRequested;
        String continuation = gateway.continuationOf(id).orElse(null);
        boolean continuable = owner && settled && continuation == null && AgentGateway.continuable(snapshot);
        boolean waiting = owner && active && snapshot.status == ExecutionStatus.waiting_for_user;
        boolean approvals = snapshot.calls.stream().anyMatch(AcceptedCall::awaitingApproval);
        return new Actions(
                snapshot.revision,
                owner,
                settled,
                owner && active,
                waiting && !snapshot.pendingQuestions.isEmpty(),
                waiting && approvals,
                active,
                snapshot.status.terminal() && settled,
                continuable,
                continuation);
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Map<String, String>> missing() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Execution not found"));
    }
}
