package net.velocity.operator;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import net.velocity.agent.spi.SystemStatusContributor;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Explicit allowlist of operator-visible configuration; never serialize configuration beans or exceptions. The
 * document is the ordered union of every {@link SystemStatusContributor}; see that interface for the contract.
 */
@RestController
@RequestMapping("/api/operator/status")
public final class OperatorStatusController {

    private final List<SystemStatusContributor> contributors;
    private final ObjectProvider<MongoTemplate> mongo;
    private final Clock clock;

    public OperatorStatusController(
            ObjectProvider<SystemStatusContributor> contributors,
            ObjectProvider<MongoTemplate> mongo,
            Clock clock) {
        this.contributors = contributors.orderedStream().toList();
        var keys = new HashSet<String>();
        for (SystemStatusContributor contributor : this.contributors) {
            String key = contributor.key();
            if (key == null || !SystemStatusContributor.KEY.matcher(key).matches() || !keys.add(key)) {
                throw new IllegalArgumentException("Invalid or duplicate status section key: " + key);
            }
        }
        this.mongo = mongo;
        this.clock = clock;
    }

    public record Scheduling(
            boolean enabled,
            boolean workerEnabled,
            String workerState,
            String maximumLateness) {}

    public record Mongo(boolean configured, String connectivity, Instant checkedAt) {}

    public record Secrets(boolean enabled, boolean available) {}

    @GetMapping
    Map<String, Object> status(HttpServletRequest request) throws IOException {
        PlaygroundRequests.empty(request);
        var document = new LinkedHashMap<String, Object>();
        for (SystemStatusContributor contributor : contributors) {
            document.put(contributor.key(), contributor.section());
        }
        return document;
    }

    @GetMapping("/mongo")
    Mongo checkMongo(HttpServletRequest request) throws IOException {
        PlaygroundRequests.empty(request);
        var template = mongo.getIfAvailable();
        if (template == null) {
            return new Mongo(false, "unavailable", clock.instant());
        }
        try {
            Document reply = template.executeCommand(new Document("ping", 1));
            boolean ready = reply != null && reply.get("ok") instanceof Number ok && ok.doubleValue() == 1;
            return new Mongo(true, ready ? "connected" : "unavailable", clock.instant());
        } catch (RuntimeException unavailable) {
            // Driver errors can contain credentials or hosts. Only expose the outcome of the actual ping.
            return new Mongo(true, "unavailable", clock.instant());
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException failure) {
        return ResponseEntity.badRequest().body(Map.of("error", failure.getMessage()));
    }
}
