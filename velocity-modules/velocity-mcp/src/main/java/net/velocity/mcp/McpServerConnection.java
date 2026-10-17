package net.velocity.mcp;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import net.velocity.agent.mcp.ManagedStdioTransport;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * One long-lived client session per configured server, shared by every task. Calls are serialized per server;
 * a session that fails is discarded and rebuilt on the next call, so a crashed child process or a dropped
 * connection costs one failed call rather than a restart. Remote diagnostics are never logged or returned.
 */
final class McpServerConnection implements AutoCloseable {

    enum State {
        pending, connected, unavailable, closed
    }

    /** Why a call produced no result, and whether the request may have reached the server. */
    enum FailureKind {
        not_dispatched, rejected, invalid_request, transport_failed, cancelled
    }

    static final class CallFailure extends Exception {

        private final FailureKind kind;

        private final boolean dispatched;

        CallFailure(FailureKind kind, boolean dispatched) {
            super("MCP call failed: " + kind, null, false, false);
            this.kind = kind;
            this.dispatched = dispatched;
        }

        FailureKind kind() {
            return kind;
        }

        boolean dispatched() {
            return dispatched;
        }
    }

    /** Process environment a stdio server inherits before the operator's own entries are added. */
    private static final List<String> INHERITED_ENVIRONMENT = List.of(
            "PATH",
            "Path",
            "SystemRoot",
            "SYSTEMROOT",
            "WINDIR",
            "ComSpec",
            "TEMP",
            "TMP",
            "HOME",
            "USERPROFILE",
            "APPDATA",
            "LOCALAPPDATA",
            "ProgramData",
            "PROGRAMDATA",
            "LANG",
            "LC_ALL",
            "XDG_CONFIG_HOME",
            "XDG_CACHE_HOME",
            "XDG_DATA_HOME",
            "XDG_RUNTIME_DIR");

    private static final long MAX_INPUT_BYTES = 1_048_576L;

    private static final int MAX_LIST_PAGES = 50;

    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

    private final McpProperties.Server server;

    private final Duration connectTimeout;

    private final Duration requestTimeout;

    private final int maxResultChars;

    private final ReentrantLock lock = new ReentrantLock();

    private final AtomicBoolean closed = new AtomicBoolean();

    private final AtomicBoolean transportFailed = new AtomicBoolean();

    private volatile State state = State.pending;

    private McpClientTransport transport;

    private ManagedStdioTransport stdio;

    private McpSyncClient client;

    McpServerConnection(McpProperties.Server server, McpProperties defaults) {
        this.server = server;
        this.connectTimeout = defaults.connectTimeout();
        this.requestTimeout = server.requestTimeout() == null
                ? defaults.requestTimeout()
                : server.requestTimeout();
        this.maxResultChars = server.maxResultChars() == null
                ? defaults.maxResultChars()
                : server.maxResultChars();
    }

    McpProperties.Server server() {
        return server;
    }

    String name() {
        return server.name();
    }

    State state() {
        return state;
    }

    Duration requestTimeout() {
        return requestTimeout;
    }

    int maxResultChars() {
        return maxResultChars;
    }

    /** Connects when needed and lists every tool the server publishes; a failure leaves the session reset. */
    List<McpSchema.Tool> discover() throws Exception {
        lock.lock();
        try {
            ensureConnected();
            var tools = new ArrayList<McpSchema.Tool>();
            String cursor = null;
            for (int page = 0; page < MAX_LIST_PAGES; page++) {
                McpSchema.ListToolsResult result = cursor == null
                        ? client.listTools()
                        : client.listTools(cursor);
                if (result == null) {
                    break;
                }
                if (result.tools() != null) {
                    tools.addAll(result.tools());
                }
                cursor = result.nextCursor();
                if (cursor == null || cursor.isBlank()) {
                    break;
                }
            }
            state = State.connected;
            return tools;
        } catch (Exception unavailable) {
            reset();
            state = State.unavailable;
            throw unavailable;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Calls one tool. {@code remainingMillis} is the task's remaining operation time, which bounds the pipe
     * write of a stdio server; the SDK request timeout and the executor's own deadline bound the wait.
     */
    McpSchema.CallToolResult call(String tool, Map<String, Object> arguments, long remainingMillis)
            throws CallFailure {
        lock.lock();
        var dispatched = new AtomicBoolean();
        try {
            if (closed.get()) {
                throw new CallFailure(FailureKind.not_dispatched, false);
            }
            try {
                ensureConnected();
            } catch (Exception unavailable) {
                state = State.unavailable;
                throw new CallFailure(FailureKind.not_dispatched, false);
            }
            if (stdio != null) {
                long started = System.nanoTime();
                long allowance = Math.min(remainingMillis, requestTimeout.toMillis());
                stdio.setWriteTimeoutMillis(
                        () -> Math.max(
                                0,
                                allowance - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
                stdio.setCallDispatch(write -> {
                    dispatched.set(true);
                    write.run();
                });
            } else {
                dispatched.set(true);
            }
            try {
                McpSchema.CallToolResult result = client
                        .callTool(new McpSchema.CallToolRequest(tool, arguments));
                state = State.connected;
                return result;
            } catch (McpError rejected) {
                // A JSON-RPC error is the server's answer: the request was received.
                Integer code = rejected.getJsonRpcError() == null ? null : rejected.getJsonRpcError().code();
                boolean invalid = code != null && code == McpSchema.ErrorCodes.INVALID_PARAMS;
                throw new CallFailure(invalid ? FailureKind.invalid_request : FailureKind.rejected, true);
            } catch (Exception failed) {
                // A timed-out, interrupted or broken request leaves the session unusable; rebuild it next time.
                reset();
                state = State.unavailable;
                throw new CallFailure(
                        interrupted(failed) ? FailureKind.cancelled : FailureKind.transport_failed,
                        dispatched.get());
            }
        } finally {
            if (stdio != null) {
                stdio.setCallDispatch(Runnable::run);
                stdio.setWriteTimeoutMillis(requestTimeout::toMillis);
            }
            lock.unlock();
        }
    }

    private static boolean interrupted(Throwable failure) {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 8; depth++) {
            if (cause instanceof InterruptedException
                    || cause instanceof java.util.concurrent.CancellationException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private void ensureConnected() throws Exception {
        if (closed.get()) {
            throw new IllegalStateException("MCP connection closed");
        }
        if (client != null && alive()) {
            return;
        }
        reset();
        connect();
    }

    private boolean alive() {
        return stdio != null ? stdio.alive() : !transportFailed.get();
    }

    private void connect() throws Exception {
        McpClientTransport created = server.stdio() ? stdioTransport() : httpTransport();
        created.setExceptionHandler(ignored -> transportFailed.set(true));
        McpSyncClient createdClient = McpClient.sync(created)
                .clientInfo(new McpSchema.Implementation("velocity-mcp", "1.0"))
                .initializationTimeout(connectTimeout)
                .requestTimeout(requestTimeout)
                .loggingConsumer(ignored -> {})
                .enableCallToolSchemaCaching(false)
                .build();
        transport = created;
        stdio = created instanceof ManagedStdioTransport managed ? managed : null;
        client = createdClient;
        transportFailed.set(false);
        try {
            createdClient.initialize();
        } catch (Exception unavailable) {
            reset();
            throw unavailable;
        }
        state = State.connected;
    }

    private ManagedStdioTransport stdioTransport() {
        var environment = new LinkedHashMap<String, String>();
        for (String name : INHERITED_ENVIRONMENT) {
            String value = System.getenv(name);
            if (value != null) {
                environment.put(name, value);
            }
        }
        environment.putAll(server.environment());
        Path directory = (server.directory().isEmpty() ? Path.of("") : Path.of(server.directory()))
                .toAbsolutePath().normalize();
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException("MCP server working directory is missing");
        }
        var command = new ArrayList<String>();
        command.add(server.command());
        command.addAll(server.args());
        var created = new ManagedStdioTransport(
                command,
                environment,
                directory,
                MAX_INPUT_BYTES,
                lineBudget());
        created.setWriteTimeoutMillis(requestTimeout::toMillis);
        return created;
    }

    private McpClientTransport httpTransport() {
        URI endpoint = server.endpoint();
        String base = endpoint.getScheme() + "://" + endpoint.getRawAuthority();
        String path = endpoint.getRawPath() == null || endpoint.getRawPath().isEmpty()
                ? "/mcp"
                : endpoint.getRawPath();
        if (endpoint.getRawQuery() != null) {
            path = path + "?" + endpoint.getRawQuery();
        }
        Map<String, String> headers = server.headers();
        return HttpClientStreamableHttpTransport.builder(base)
                .endpoint(path)
                .connectTimeout(Duration.ofSeconds(10))
                .maxResponseSize((int) Math.min(Integer.MAX_VALUE, lineBudget()))
                .resumableStreams(false)
                .openConnectionOnStartup(false)
                .httpRequestCustomizer(
                        (builder, method, uri, body, context) -> headers.forEach(builder::header))
                .build();
    }

    /** JSON escaping can quadruple a result; anything larger than this fails the call rather than the process. */
    private long lineBudget() {
        return 4L * maxResultChars + 65_536L;
    }

    private void reset() {
        McpSyncClient currentClient = client;
        McpClientTransport currentTransport = transport;
        client = null;
        transport = null;
        stdio = null;
        transportFailed.set(false);
        if (currentTransport instanceof ManagedStdioTransport managed) {
            managed.close();
        } else if (currentTransport != null) {
            try {
                currentTransport.closeGracefully().block(CLOSE_TIMEOUT);
            } catch (Exception ignored) {
                // The remote side may already be gone; nothing to recover.
            }
        }
        if (currentClient != null) {
            try {
                currentClient.close();
            } catch (Exception ignored) {
                // Closing an already-failed session has no further effect.
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        lock.lock();
        try {
            reset();
            state = State.closed;
        } finally {
            lock.unlock();
        }
    }
}
