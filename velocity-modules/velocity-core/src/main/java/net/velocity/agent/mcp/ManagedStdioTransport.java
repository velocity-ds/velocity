package net.velocity.agent.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

import net.velocity.inference.InferenceDeadlineScope;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/** Process I/O for the official SDK client; remote diagnostics are never logged. */
public final class ManagedStdioTransport implements McpClientTransport, AutoCloseable {

    private static final java.util.concurrent.ScheduledExecutorService WRITE_WATCHDOG = java.util.concurrent.Executors
            .newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon().name("velocity-mcp-write-watchdog").factory());
    private final McpJsonMapper mapper = McpJsonDefaults.getMapper();
    private final List<String> command;
    private final Map<String, String> environment;
    private final Path directory;
    private final long maximumLineCharacters;
    private final long maximumInputBytes;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final java.util.Set<ProcessHandle> ownedChildren = java.util.concurrent.ConcurrentHashMap
            .newKeySet();
    private volatile Process process;
    private volatile OutputStream output;
    private volatile Consumer<Throwable> failureHandler = ignored -> {};
    private volatile Function<McpSchema.JSONRPCMessage, McpSchema.JSONRPCMessage> sanitizer = Function
            .identity();
    private volatile Function<McpSchema.JSONRPCMessage, McpSchema.JSONRPCMessage> outbound = Function
            .identity();
    private volatile Consumer<Runnable> callDispatch = Runnable::run;
    private volatile LongSupplier writeTimeoutMillis;

    public ManagedStdioTransport(
            List<String> command,
            Map<String, String> environment,
            Path directory,
            int observationLimit) {
        this(
                command,
                environment,
                directory,
                Long.MAX_VALUE,
                4096L + 2L * encodedResultLimit(observationLimit));
    }

    /** General MCP process boundary; budgets and authentication remain adapter-owned. */
    public ManagedStdioTransport(
            List<String> command,
            Map<String, String> environment,
            Path directory,
            long maximumInputBytes,
            long maximumLineCharacters) {
        this.command = List.copyOf(command);
        this.environment = Map.copyOf(environment);
        this.directory = directory;
        if (maximumInputBytes < 1 || maximumLineCharacters < 1) {
            throw new IllegalArgumentException("Invalid MCP budget");
        }
        this.maximumInputBytes = maximumInputBytes;
        this.maximumLineCharacters = maximumLineCharacters;
    }

    public static long encodedResultLimit(int observationLimit) {
        if (observationLimit < 1) {
            throw new IllegalArgumentException("A positive observation limit is required");
        }
        // Python counts code points; a non-BMP point needs two escaped UTF-16 units.
        return 262144L + 12L * observationLimit;
    }

    @Override
    public void setExceptionHandler(Consumer<Throwable> handler) {
        failureHandler = handler;
    }

    public void setMessageSanitizer(Function<McpSchema.JSONRPCMessage, McpSchema.JSONRPCMessage> sanitizer) {
        this.sanitizer = sanitizer;
    }

    /** Inject private initialization only after SDK processing, never into its diagnostic objects. */
    public void setOutboundMapper(Function<McpSchema.JSONRPCMessage, McpSchema.JSONRPCMessage> mapper) {
        outbound = mapper;
    }

    /** Execute the actual tools/call write inside a trusted connection/intent guard. */
    public void setCallDispatch(Consumer<Runnable> guard) {
        callDispatch = guard;
    }

    /** Task adapters supply the original deadline; standalone transports retain their pipe bound. */
    public void setWriteTimeoutMillis(LongSupplier remainingMillis) {
        writeTimeoutMillis = java.util.Objects.requireNonNull(remainingMillis);
    }

    @Override
    public Mono<Void> connect(
            Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        return Mono.fromRunnable(() -> {
            if (closed.get()) {
                throw failure();
            }
            try {
                var builder = new ProcessBuilder(command).directory(directory.toFile());
                builder.environment().clear();
                builder.environment().putAll(environment);
                process = builder.start();
                output = process.getOutputStream();
                Thread.ofPlatform().daemon().name("velocity-mcp-input").start(() -> read(handler));
                Thread.ofPlatform().daemon().name("velocity-mcp-errors").start(() -> {
                    try (var stderr = process.getErrorStream()) {
                        byte[] bytes = new byte[4096];
                        while (stderr.read(bytes) != -1) {
                        }
                    } catch (Exception ignored) {
                    }
                });
            } catch (Exception unavailable) {
                close();
                throw failure();
            }
        });
    }

    private void read(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        try (var reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            while (!closed.get()) {
                String line = readLine(reader);
                if (line == null) {
                    break;
                }
                trackChildren();
                var message = McpSchema.deserializeJsonRpcMessage(mapper, line);
                if (message instanceof McpSchema.JSONRPCResponse response && response.error() != null) {
                    message = McpSchema.JSONRPCResponse.error(
                            response.id(),
                            new McpSchema.JSONRPCResponse.JSONRPCError(
                                    response.error().code(),
                                    "MCP server rejected the request"));
                }
                message = sanitizer.apply(message);
                handler.apply(Mono.just(message)).subscribe(ignored -> {}, ignored -> fail());
            }
        } catch (Exception ignored) {
        }
        if (!closed.get()) {
            fail();
        }
    }

    private String readLine(BufferedReader reader) throws IOException {
        var line = new StringBuilder();
        int character;
        while ((character = reader.read()) != -1) {
            if (character == '\n') {
                return line.toString();
            }
            if (character == '\r') {
                reader.mark(1);
                if (reader.read() != '\n') {
                    reader.reset();
                }
                return line.toString();
            }
            if (line.length() >= maximumLineCharacters) {
                throw new IOException("MCP result exceeds its admitted transport budget");
            }
            line.append((char) character);
        }
        return line.isEmpty() ? null : line.toString();
    }

    private void fail() {
        close();
        failureHandler.accept(failure());
    }

    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
        var deadline = writeTimeoutMillis;
        long remainingMillis = deadline == null
                ? InferenceDeadlineScope.remainingOr(Duration.ofSeconds(5)).toMillis()
                : deadline.getAsLong();
        long started = System.nanoTime();
        return Mono.fromRunnable(() -> {
            try {
                if (!alive() || output == null) {
                    throw failure();
                }
                trackChildren();
                byte[] bytes = (mapper.writeValueAsString(outbound.apply(message)) + "\n")
                        .getBytes(StandardCharsets.UTF_8);
                if (bytes.length > maximumInputBytes) {
                    throw failure();
                }
                Thread sender = Thread.currentThread();
                // Process pipe writes can block independently of MCP response timeouts. Killing the
                // process from another thread unblocks the pipe without acquiring a connection lock.
                var completed = new AtomicBoolean();
                var watchdog = WRITE_WATCHDOG.scheduleAtFixedRate(() -> {
                    if (!completed.get() && (sender.isInterrupted()
                            || remainingMillis != Long.MAX_VALUE
                                    && System.nanoTime() - started >= TimeUnit.MILLISECONDS
                                            .toNanos(remainingMillis))) {
                        close();
                    }
                }, 100, 100, TimeUnit.MILLISECONDS);
                Runnable write = () -> {
                    try {
                        synchronized (output) {
                            output.write(bytes);
                            output.flush();
                        }
                    } catch (IOException unavailable) {
                        throw failure();
                    }
                };
                try {
                    if (message instanceof McpSchema.JSONRPCRequest request
                            && "tools/call".equals(request.method())) {
                        callDispatch.accept(write);
                    } else {
                        write.run();
                    }
                } finally {
                    completed.set(true);
                    watchdog.cancel(false);
                    java.util.Arrays.fill(bytes, (byte) 0);
                }
            } catch (Exception ignored) {
                fail();
                throw failure();
            }
        });
    }

    public boolean alive() {
        return !closed.get() && process != null && process.isAlive();
    }

    @Override
    public <T> T unmarshalFrom(Object data, TypeRef<T> type) {
        return mapper.convertValue(data, type);
    }

    @Override
    public Mono<Void> closeGracefully() {
        return Mono.fromRunnable(this::close);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        var current = process;
        if (current == null) {
            return;
        }
        trackChildren();
        for (var child : List.copyOf(ownedChildren)) {
            if (child.isAlive()) {
                ownedChildren.addAll(child.descendants().toList());
            }
        }
        var children = List.copyOf(ownedChildren);
        children.reversed().forEach(ProcessHandle::destroy);
        current.destroy();
        try {
            current.waitFor(500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        children.reversed().forEach(child -> {
            if (child.isAlive()) {
                child.destroyForcibly();
            }
        });
        if (current.isAlive()) {
            current.destroyForcibly();
        }
    }

    private void trackChildren() {
        var current = process;
        if (current != null) {
            ownedChildren.addAll(current.descendants().toList());
        }
    }

    private static IllegalStateException failure() {
        return new IllegalStateException("MCP process transport unavailable", null);
    }
}
