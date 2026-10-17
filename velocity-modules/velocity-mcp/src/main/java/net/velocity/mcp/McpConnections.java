package net.velocity.mcp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.velocity.agent.ToolRegistry;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * The configured servers for one installation: discovery at startup, the registered tool names per server, and
 * the safe view the status section and operator route expose. Discovery failures are reported, never fatal,
 * so an unreachable server leaves the rest of Velocity running; its tools appear after a restart once it answers.
 */
public final class McpConnections implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpConnections.class);

    /** Safe per-server view: configured values, the derived tool names and counts only. */
    public record ServerView(
            String name,
            String transport,
            String state,
            List<String> tools,
            int skippedTools,
            List<String> missingTools) {}

    private final McpProperties properties;

    private final List<McpServerConnection> connections;

    private final Map<String, List<String>> registered = new ConcurrentHashMap<>();

    private final Map<String, Integer> skipped = new ConcurrentHashMap<>();

    private final Map<String, List<String>> missing = new ConcurrentHashMap<>();

    public McpConnections(McpProperties properties) {
        this.properties = properties;
        var created = new ArrayList<McpServerConnection>();
        for (McpProperties.Server server : properties.servers()) {
            created.add(new McpServerConnection(server, properties));
        }
        connections = List.copyOf(created);
    }

    /** Connects to each server in turn and registers its allowed tools; called once while the registry is built. */
    public void register(ToolRegistry registry) {
        for (McpServerConnection connection : connections) {
            String name = connection.name();
            List<McpSchema.Tool> tools;
            try {
                tools = connection.discover();
            } catch (Exception unavailable) {
                log.warn(
                        "stage=mcp_discovery outcome=unavailable server={} failure={}",
                        name,
                        unavailable.getClass().getSimpleName());
                registered.put(name, List.of());
                skipped.put(name, 0);
                missing.put(name, List.copyOf(connection.server().tools()));
                continue;
            }
            List<String> allowlist = connection.server().tools();
            var allowed = new LinkedHashSet<>(allowlist);
            var names = new ArrayList<String>();
            var seen = new HashSet<String>();
            int skippedCount = 0;
            for (McpSchema.Tool tool : tools) {
                if (tool == null || tool.name() == null || tool.name().isBlank()) {
                    skippedCount++;
                    continue;
                }
                if (!allowlist.isEmpty() && !allowlist.contains(tool.name())) {
                    continue;
                }
                allowed.remove(tool.name());
                if (names.size() >= McpProperties.MAX_ALLOWED_TOOLS) {
                    skippedCount++;
                    continue;
                }
                String registeredName = McpSchemas.registeredName(name, tool.name());
                if (!seen.add(registeredName) || registry.find(registeredName).isPresent()) {
                    skippedCount++;
                    log.warn(
                            "stage=mcp_discovery outcome=name_collision server={} tool={}",
                            name,
                            registeredName);
                    continue;
                }
                try {
                    registry.register(McpTools.definition(connection, properties, tool, registeredName));
                    names.add(registeredName);
                } catch (IllegalArgumentException invalid) {
                    skippedCount++;
                    log.warn(
                            "stage=mcp_discovery outcome=tool_rejected server={} tool={}",
                            name,
                            registeredName);
                }
            }
            registered.put(name, List.copyOf(names));
            skipped.put(name, skippedCount);
            missing.put(name, List.copyOf(allowed));
            log.info(
                    "stage=mcp_discovery outcome=registered server={} tools={} skipped={} missing={}",
                    name,
                    names.size(),
                    skippedCount,
                    allowed.size());
        }
    }

    public int registeredToolCount() {
        return registered.values().stream().mapToInt(List::size).sum();
    }

    public List<ServerView> views() {
        var views = new ArrayList<ServerView>();
        for (McpServerConnection connection : connections) {
            String name = connection.name();
            views.add(
                    new ServerView(
                            name,
                            connection.server().transport(),
                            connection.state().name(),
                            registered.getOrDefault(name, List.of()),
                            skipped.getOrDefault(name, 0),
                            missing.getOrDefault(name, List.of())));
        }
        return List.copyOf(views);
    }

    /** The status section: configured names, transports, states and counts; never remote text. */
    public Map<String, Object> status() {
        var servers = new ArrayList<Map<String, Object>>();
        for (ServerView view : views()) {
            var server = new LinkedHashMap<String, Object>();
            server.put("name", view.name());
            server.put("transport", view.transport());
            server.put("state", view.state());
            server.put("registeredTools", view.tools().size());
            server.put("skippedTools", view.skippedTools());
            server.put("missingTools", view.missingTools().size());
            servers.add(Map.copyOf(server));
        }
        var section = new LinkedHashMap<String, Object>();
        section.put("servers", List.copyOf(servers));
        section.put("registeredTools", registeredToolCount());
        return Map.copyOf(section);
    }

    @Override
    public void close() {
        for (McpServerConnection connection : connections) {
            connection.close();
        }
    }
}
