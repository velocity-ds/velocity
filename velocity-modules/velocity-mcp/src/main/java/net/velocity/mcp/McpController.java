package net.velocity.mcp;

import java.io.IOException;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Read-only operator view of the configured servers and the tool names registered from each. Mounted under
 * {@code /api/operator}, so the local operator boundary applies; the view carries configured names, derived
 * tool names, states and counts only, never a server's own text, command lines, URLs or headers.
 */
@RestController
public final class McpController {

    private final McpProperties properties;

    private final ObjectProvider<McpConnections> connections;

    public McpController(McpProperties properties, ObjectProvider<McpConnections> connections) {
        this.properties = properties;
        this.connections = connections;
    }

    public record View(boolean enabled, boolean confirmWrites, List<McpConnections.ServerView> servers) {}

    @GetMapping("/api/operator/mcp")
    ResponseEntity<View> servers(HttpServletRequest request) throws IOException {
        if (request.getQueryString() != null || request.getInputStream().read() != -1) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "The MCP route accepts no body or query parameters");
        }
        McpConnections servers = connections.getIfAvailable();
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(
                        new View(
                                properties.enabled(),
                                properties.enabled() && properties.confirmWrites(),
                                servers == null ? List.of() : servers.views()));
    }
}
