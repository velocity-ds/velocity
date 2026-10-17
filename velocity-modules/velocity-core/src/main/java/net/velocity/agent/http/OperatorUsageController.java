package net.velocity.agent.http;

import java.io.IOException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import net.velocity.agent.AgentGateway;
import net.velocity.agent.persistence.NotFoundException;
import net.velocity.usage.UsageReports;

import jakarta.servlet.http.HttpServletRequest;

/** Account scope is resolved only here, behind LocalOperatorFilter; query parameters never carry authority. */
@RestController
@RequestMapping("/api/operator")
public final class OperatorUsageController {

    private final LocalAccountScope account;
    private final AgentGateway gateway;
    private final UsageReports reports;

    public OperatorUsageController(LocalAccountScope account, AgentGateway gateway, UsageReports reports) {
        this.account = account;
        this.gateway = gateway;
        this.reports = reports;
    }

    @GetMapping("/usage/summary")
    ResponseEntity<UsageReports.Summary> summary(HttpServletRequest request) throws IOException {
        parameters(request, Set.of());
        return response(reports.summary(account.scope().requireOwnerAccountId()));
    }

    @GetMapping("/usage")
    ResponseEntity<UsageReports.Report> range(HttpServletRequest request) throws IOException {
        parameters(request, Set.of("from", "to", "provider", "model", "activity"));
        String owner = account.scope().requireOwnerAccountId();
        var from = LocalDate.parse(required(request, "from"));
        var to = LocalDate.parse(required(request, "to"));
        return response(
                reports.range(
                        owner,
                        from,
                        to,
                        optional(request, "provider"),
                        optional(request, "model"),
                        optional(request, "activity")));
    }

    @GetMapping("/tasks/{id}/usage")
    ResponseEntity<UsageReports.Report> task(@PathVariable String id, HttpServletRequest request)
            throws IOException {
        parameters(request, Set.of());
        return response(task(account.scope().requireOwnerAccountId(), id));
    }

    @GetMapping("/usage/tasks")
    ResponseEntity<Map<String, Map<String, UsageReports.Report>>> tasks(HttpServletRequest request)
            throws IOException {
        parameters(request, Set.of("ids"));
        String owner = account.scope().requireOwnerAccountId();
        var ids = required(request, "ids").split(",", -1);
        if (ids.length > 100) {
            throw new IllegalArgumentException("At most 100 task IDs are supported");
        }
        var tasks = new LinkedHashMap<String, UsageReports.Report>();
        for (String id : ids) {
            identifier(id);
            try {
                tasks.put(id, task(owner, id));
            } catch (NotFoundException missing) {
                /* Administrative task lists can include another account; disclose no usage. */ }
        }
        return response(Map.of("tasks", tasks));
    }

    private UsageReports.Report task(String owner, String id) {
        identifier(id);
        var snapshot = gateway.get(id);
        if (snapshot.scope == null || !owner.equals(snapshot.scope.ownerAccountId())) {
            throw new NotFoundException("Task not found");
        }
        return reports.task(
                owner,
                id,
                snapshot.totalTokens,
                UsageReports.expectedTaskRequests(snapshot),
                snapshot.usageSources.keySet(),
                snapshot.modelTurns);
    }

    private static <T> ResponseEntity<T> response(T value) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(value);
    }

    private static void identifier(String id) {
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")) {
            throw new IllegalArgumentException("Invalid task ID");
        }
    }

    private static String required(HttpServletRequest request, String key) {
        String value = optional(request, key);
        if (value == null) {
            throw new IllegalArgumentException("Missing query parameter");
        }
        return value;
    }

    private static String optional(HttpServletRequest request, String key) {
        String value = request.getParameter(key);
        if (value == null) {
            return null;
        }
        if (value.isBlank() || value.length() > (key.equals("ids") ? 13000 : 256)) {
            throw new IllegalArgumentException("Invalid query parameter");
        }
        return value;
    }

    private static void parameters(HttpServletRequest request, Set<String> allowed) throws IOException {
        if (request.getInputStream().read() != -1 || !allowed.containsAll(request.getParameterMap().keySet())
                || request.getParameterMap().values().stream().anyMatch(values -> values.length != 1)) {
            throw new IllegalArgumentException("Unsupported body or query parameters");
        }
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Map<String, String>> missing() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Task not found"));
    }

    @ExceptionHandler({IllegalArgumentException.class, java.time.DateTimeException.class})
    ResponseEntity<Map<String, String>> invalid() {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid usage report request"));
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<Map<String, String>> unavailable(RuntimeException failure) {
        if (failure instanceof ResponseStatusException status) {
            throw status;
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Cache-Control", "no-store")
                .body(Map.of("error", "Usage accounting unavailable"));
    }
}
