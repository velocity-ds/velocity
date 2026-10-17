package net.velocity.agent.browser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import net.velocity.agent.ToolRegistry;
import net.velocity.agent.mcp.ManagedStdioTransport;
import net.velocity.agent.security.BrowserDestinationPolicy;
import net.velocity.agent.security.BrowserRejectedException;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;

/** Browser Use controls pages over CDP; the existing trusted hook guards Chromium requests. */
final class BrowserUseSession implements BrowserService.Session {

    static final String VERSION = "0.13.10";
    private final BrowserNetworkGuard guard;
    private final ManagedStdioTransport transport;
    private final McpSyncClient client;
    private final Path directory;
    private final ResponseSanitizer responses;
    private final ReentrantLock calls = new ReentrantLock();
    private final AtomicBoolean closed = new AtomicBoolean();

    BrowserUseSession(
            BrowserProperties properties,
            BrowserDestinationPolicy policy,
            int observationLimit,
            ToolRegistry.Context context) {
        int sourceLimit = Math.max(observationLimit, BrowserService.MAX_SOURCE_CHARS);
        responses = new ResponseSanitizer(sourceLimit);
        BrowserNetworkGuard createdGuard = null;
        ManagedStdioTransport createdTransport = null;
        McpSyncClient createdClient = null;
        Path createdDirectory = null;
        try {
            Path launcher = Path.of(properties.launcherScript()).toAbsolutePath().normalize();
            Path modules = launcher.getParent().resolve("node_modules");
            Path hook = launcher.getParent().resolve("network-guard.cjs");
            if (!Files.isRegularFile(launcher) || !Files.isRegularFile(hook)) {
                throw new IllegalStateException();
            }
            Path root = Path.of("target/browser-use").toAbsolutePath().normalize();
            Files.createDirectories(root);
            createdDirectory = Files.createTempDirectory(root, "session-");
            createdGuard = new BrowserNetworkGuard(policy);
            var environment = new LinkedHashMap<String, String>();
            for (String name : List.of(
                    "PATH",
                    "Path",
                    "SystemRoot",
                    "SYSTEMROOT",
                    "WINDIR",
                    "USERPROFILE",
                    "LOCALAPPDATA",
                    "APPDATA",
                    "TEMP",
                    "TMP",
                    "PLAYWRIGHT_BROWSERS_PATH",
                    "DISPLAY",
                    "WAYLAND_DISPLAY",
                    "XDG_RUNTIME_DIR")) {
                String value = System.getenv(name);
                if (value != null) {
                    environment.put(name, value);
                }
            }
            environment.putAll(createdGuard.environment());
            environment.put(
                    "VELOCITY_BROWSER_PRIVATE_ORIGINS",
                    McpJsonDefaults.getMapper().writeValueAsString(properties.privateOrigins()));
            environment
                    .put("VELOCITY_BROWSER_USE_PLAYWRIGHT_MODULE", modules.resolve("playwright").toString());
            environment.put("VELOCITY_BROWSER_USE_NETWORK_HOOK", hook.toString());
            environment.put("VELOCITY_BROWSER_MAX_SOURCE_CHARS", Integer.toString(sourceLimit));
            environment.put(
                    "VELOCITY_BROWSER_TASK_REMAINING_MILLIS",
                    Long.toString(context.remainingOperationMillis()));
            environment.put("ANONYMIZED_TELEMETRY", "false");
            environment.put("BROWSER_USE_SETUP_LOGGING", "false");
            environment.put("BROWSER_USE_LOGGING_LEVEL", "critical");
            environment.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
            createdTransport = new ManagedStdioTransport(
                    List.of(
                            properties.nodeExecutable(),
                            launcher.toString(),
                            "--python",
                            properties.pythonExecutable(),
                            "--headless",
                            Boolean.toString(properties.headless()),
                            "--max-observation-chars",
                            Integer.toString(observationLimit)),
                    environment,
                    createdDirectory,
                    sourceLimit);
            createdTransport.setMessageSanitizer(responses::sanitize);
            createdTransport.setWriteTimeoutMillis(context::remainingOperationMillis);
            long remainingMillis = context.remainingOperationMillis();
            createdClient = McpClient.sync(createdTransport)
                    .clientInfo(new McpSchema.Implementation("velocity-browser-use", "1.0"))
                    .initializationTimeout(
                            remainingMillis == Long.MAX_VALUE
                                    ? Duration.ofNanos(Long.MAX_VALUE)
                                    : Duration.ofMillis(Math.max(1, remainingMillis)))
                    .requestTimeout(Duration.ofNanos(Long.MAX_VALUE))
                    .loggingConsumer(ignored -> {}).enableCallToolSchemaCaching(false).build();
            createdClient.initialize();
            var tools = createdClient.listTools().tools().stream().map(McpSchema.Tool::name).toList();
            if (!tools.containsAll(BrowserService.SUPPORTED_TOOLS)) {
                throw new IllegalStateException();
            }
        } catch (Exception unavailable) {
            if (createdTransport != null) {
                createdTransport.close();
            }
            if (createdClient != null) {
                createdClient.close();
            }
            if (createdGuard != null) {
                createdGuard.close();
            }
            removeDirectory(createdDirectory);
            throw new BrowserRejectedException(
                    "browser_use_unavailable",
                    "The pinned Browser Use runtime is unavailable.");
        }
        guard = createdGuard;
        transport = createdTransport;
        client = createdClient;
        directory = createdDirectory;
    }

    @Override
    public Map<String, Object> call(String tool, Map<String, Object> input) {
        return call(tool, input, Long.MAX_VALUE);
    }

    @Override
    public Map<String, Object> call(String tool, Map<String, Object> input, long remainingMillis) {
        calls.lock();
        try {
            if (closed.get()) {
                throw new IllegalStateException();
            }
            long started = System.nanoTime();
            transport.setWriteTimeoutMillis(
                    () -> remainingMillis == Long.MAX_VALUE
                            ? Long.MAX_VALUE
                            : Math.max(
                                    0,
                                    remainingMillis - java.util.concurrent.TimeUnit.NANOSECONDS
                                            .toMillis(System.nanoTime() - started)));
            var result = client
                    .callTool(new McpSchema.CallToolRequest(tool, nativeArguments(input, remainingMillis)));
            if (Boolean.TRUE.equals(result.isError())) {
                return Map.of("status", "error");
            }
            if (result.structuredContent() != null || result.content().size() != 1
                    || !(result.content().getFirst() instanceof McpSchema.TextContent text)) {
                throw new IllegalStateException("Unsupported Browser Use result");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = McpJsonDefaults.getMapper().readValue(text.text(), Map.class);
            return responses.restore(payload);
        } catch (Exception invalid) {
            throw new IllegalStateException("Invalid Browser Use result", null);
        } finally {
            responses.clearResult();
            calls.unlock();
        }
    }

    static Map<String, Object> nativeArguments(Map<String, Object> input, long remainingMillis) {
        if (remainingMillis <= 0 || input.containsKey("_velocity_remaining_millis")) {
            throw new IllegalArgumentException("A trusted remaining task deadline is required");
        }
        var arguments = new LinkedHashMap<>(input);
        arguments.put("_velocity_remaining_millis", remainingMillis);
        return arguments;
    }

    @Override
    public BrowserNetworkGuard networkGuard() {
        return guard;
    }

    @Override
    public boolean alive() {
        return !closed.get() && transport.alive();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        transport.close();
        client.close();
        guard.close();
        responses.clear();
        removeDirectory(directory);
    }

    /** Keep the complete native result out of SDK diagnostics until trusted service validation. */
    static final class ResponseSanitizer {

        private static final String PRIVATE_RESULT = "_velocity_private_result";
        private final long maximumEncodedCharacters;
        private String token;
        private Map<String, Object> privateResult;
        private boolean closed;

        ResponseSanitizer(int observationLimit) {
            maximumEncodedCharacters = ManagedStdioTransport.encodedResultLimit(observationLimit);
        }

        synchronized McpSchema.JSONRPCMessage sanitize(McpSchema.JSONRPCMessage message) {
            try {
                if (closed) {
                    throw new IllegalStateException();
                }
                var json = McpJsonDefaults.getMapper();
                var envelope = object(json.convertValue(message, Map.class));
                if (!(envelope.get("result") instanceof Map<?, ?> result)) {
                    return message;
                }
                if (!result.containsKey("content")) {
                    return message;
                } // Initialization and discovery have no page data.
                if (privateResult != null || result.get("structuredContent") != null
                        || !(result.get("content") instanceof List<?> content) || content.size() != 1
                        || !(content.getFirst() instanceof Map<?, ?> item) || !"text".equals(item.get("type"))
                        || !(item.get("text") instanceof String encoded)
                        || encoded.length() > maximumEncodedCharacters) {
                    throw new IllegalStateException();
                }
                var payload = object(json.readValue(encoded, Map.class));
                if (!Set.of("ok", "error").contains(payload.get("status"))) {
                    throw new IllegalStateException();
                }
                privateResult = payload;
                token = UUID.randomUUID().toString();
                var placeholder = Map
                        .of("type", "text", "text", json.writeValueAsString(Map.of(PRIVATE_RESULT, token)));
                envelope.put(
                        "result",
                        Map.of(
                                "content",
                                List.of(placeholder),
                                "isError",
                                Boolean.TRUE.equals(result.get("isError"))));
                return McpSchema.deserializeJsonRpcMessage(json, json.writeValueAsString(envelope));
            } catch (Exception invalid) {
                clearResult();
                throw new IllegalStateException("Browser response rejected", null);
            }
        }

        synchronized Map<String, Object> restore(Map<String, Object> payload) {
            try {
                if (closed || privateResult == null || payload == null
                        || !Set.of(PRIVATE_RESULT).equals(payload.keySet())
                        || !token.equals(payload.get(PRIVATE_RESULT))) {
                    throw new IllegalStateException();
                }
                return privateResult;
            } finally {
                clearResult();
            }
        }

        private static LinkedHashMap<String, Object> object(Map<?, ?> value) {
            var result = new LinkedHashMap<String, Object>();
            value.forEach((key, item) -> {
                if (!(key instanceof String name)) {
                    throw new IllegalStateException();
                }
                result.put(name, item);
            });
            return result;
        }

        synchronized void clearResult() {
            token = null;
            privateResult = null;
        }

        synchronized void clear() {
            closed = true;
            clearResult();
        }
    }

    private static void removeDirectory(Path directory) {
        if (directory == null || !directory.toAbsolutePath().normalize()
                .startsWith(Path.of("target/browser-use").toAbsolutePath().normalize())) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (Exception ignored) {
            /* A crashed browser can leave temporary output; never restore or replay it. */ }
    }
}
