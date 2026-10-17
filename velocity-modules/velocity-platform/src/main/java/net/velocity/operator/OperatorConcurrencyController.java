package net.velocity.operator;

import java.io.IOException;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import net.velocity.agent.AgentGateway;
import net.velocity.inference.InferenceRequestLimiter;

import jakarta.servlet.http.HttpServletRequest;

/** Safe installation capacity counters behind the ordinary local operator boundary. */
@RestController
public final class OperatorConcurrencyController {

    private final AgentGateway gateway;
    private final InferenceRequestLimiter inference;

    public OperatorConcurrencyController(AgentGateway gateway, InferenceRequestLimiter inference) {
        this.gateway = gateway;
        this.inference = inference;
    }

    public record Status(AgentGateway.ConcurrencyStatus roots, InferenceRequestLimiter.Status inference) {}

    @GetMapping("/api/operator/status/concurrency")
    Status status(HttpServletRequest request) throws IOException {
        PlaygroundRequests.empty(request);
        return new Status(gateway.concurrencyStatus(), inference.status());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<java.util.Map<String, String>> invalid() {
        return ResponseEntity.badRequest()
                .body(java.util.Map.of("error", "Concurrency status accepts no parameters or body"));
    }
}
