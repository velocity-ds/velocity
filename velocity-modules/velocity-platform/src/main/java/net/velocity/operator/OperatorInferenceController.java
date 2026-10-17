package net.velocity.operator;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import net.velocity.inference.InferenceService;
import net.velocity.inference.InferenceStream;
import net.velocity.inference.InferenceUsageScope;
import net.velocity.inference.objects.InferenceCapability;
import net.velocity.inference.objects.InferenceContent;
import net.velocity.inference.objects.InferenceEvent;
import net.velocity.inference.objects.InferenceMessage;
import net.velocity.inference.objects.InferenceRequest;
import net.velocity.inference.objects.InferenceResponse;
import net.velocity.inference.objects.PromptPreset;
import net.velocity.usage.UsageLedger;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;

/** Local model experimentation; never admits agent executions or dispatches proposed actions. */
@RestController
@RequestMapping("/api/operator/inference")
public final class OperatorInferenceController {

    private final InferenceService inference;
    private final Environment environment;
    private final PlaygroundSessions sessions;
    private final java.util.concurrent.ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final java.util.concurrent.ScheduledExecutorService heartbeats = Executors
            .newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon().name("playground-heartbeats").factory());
    private final java.util.Set<ActiveStream> active = ConcurrentHashMap.newKeySet();

    public OperatorInferenceController(InferenceService inference, Environment environment, Clock clock) {
        this.inference = inference;
        this.environment = environment;
        this.sessions = new PlaygroundSessions(clock);
    }

    public record Capabilities(
            boolean available,
            String provider,
            String model,
            List<InferenceCapability> capabilities,
            List<PromptPreset> presets,
            String connectivity,
            HistoryLimits history,
            boolean computerActionsExecute) {}

    public record HistoryLimits(int maxSessions, int maxBytes, int ttlMinutes) {}

    public record ResponseView(
            String id,
            String model,
            String text,
            List<Map<String, Object>> content,
            String stopReason,
            InferenceResponse.Usage usage) {}

    public record Result(
            String sessionId,
            long revision,
            ResponseView response,
            boolean continuationAllowed,
            boolean historyRetained) {}

    Capabilities capabilities() {
        String provider = inference.available() ? inference.providerId() : null;
        String model = provider != null
                && java.util.Set.of("claude", "openai", "llama-cpp").contains(provider)
                        ? environment.getProperty("inference." + provider + ".model")
                        : null;
        return new Capabilities(
                inference.available(),
                provider,
                model,
                inference.capabilities().stream().sorted().toList(),
                List.of(PromptPreset.values()),
                "not_checked",
                new HistoryLimits(
                        PlaygroundSessions.MAX_SESSIONS,
                        PlaygroundSessions.MAX_BYTES,
                        30),
                false);
    }

    @GetMapping
    Capabilities capabilities(HttpServletRequest request) throws IOException {
        PlaygroundRequests.empty(request);
        return capabilities();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    Result infer(@RequestBody String body, HttpServletRequest request) {
        PlaygroundRequests.noQuery(request);
        PlaygroundRequests.Request input = checked(body, false);
        PlaygroundSessions.Turn turn = sessions.begin(input);
        try (var accounting = InferenceUsageScope.open(playgroundUsage())) {
            return result(turn, inference.infer(turn.request()));
        } catch (RuntimeException failure) {
            sessions.discard(turn);
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Inference request failed; history discarded. Inspect provider configuration before starting a new request.");
        }
    }

    @PostMapping(value = "/stream", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<SseEmitter> stream(@RequestBody String body, HttpServletRequest request) {
        PlaygroundRequests.noQuery(request);
        PlaygroundRequests.Request input = checked(body, true);
        PlaygroundSessions.Turn turn = sessions.begin(input);
        var emitter = new SseEmitter(TimeUnit.MINUTES.toMillis(10));
        var current = new ActiveStream(turn, emitter);
        active.add(current);
        emitter.onCompletion(current::stop);
        emitter.onTimeout(current::stop);
        emitter.onError(error -> current.stop());
        current.heartbeat = heartbeats.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("keepalive"));
            } catch (Exception disconnected) {
                current.stop();
            }
        }, 10, 10, TimeUnit.SECONDS);
        workers.submit(() -> consume(current));
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .header("X-Accel-Buffering", "no").body(emitter);
    }

    @DeleteMapping("/sessions/{id}")
    ResponseEntity<Void> delete(@PathVariable String id, HttpServletRequest request) throws IOException {
        PlaygroundRequests.empty(request);
        sessions.delete(id);
        return ResponseEntity.noContent().build();
    }

    private PlaygroundRequests.Request checked(String body, boolean streaming) {
        var input = PlaygroundRequests.parse(body);
        if (!inference.available()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Inference is not configured");
        }
        var contents = new ArrayList<InferenceContent>(input.images());
        contents.add(new InferenceContent.Text(input.prompt()));
        var request = new InferenceRequest(
                List.of(new InferenceMessage(InferenceMessage.Role.USER, contents)),
                input.systemPrompt(),
                input.options());
        var missing = java.util.EnumSet.copyOf(request.requiredCapabilities());
        if (streaming) {
            missing.add(InferenceCapability.STREAMING);
        }
        missing.removeAll(inference.capabilities());
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Configured provider does not support " + missing);
        }
        return input;
    }

    private void consume(ActiveStream current) {
        current.worker = Thread.currentThread();
        try {
            if (current.stopped.get()) {
                return;
            }
            try (var accounting = InferenceUsageScope.open(playgroundUsage());
                    var stream = new OwnedStream(inference.stream(current.turn.request()))) {
                current.stream.set(stream);
                if (current.stopped.get()) {
                    return;
                }
                var iterator = stream.delegate.events().iterator();
                while (!current.stopped.get() && iterator.hasNext()) {
                    InferenceEvent event = iterator.next();
                    switch (event.type()) {
                        case TEXT_DELTA -> current.send(
                                "text_delta",
                                Map.of("delta", event.delta() == null ? "" : event.delta()));
                        case THINKING_DELTA -> current.send(
                                "thinking_delta",
                                Map.of("delta", event.delta() == null ? "" : event.delta()));
                        case CITATION -> current.send("citation", Map.of("citation", safe(event.metadata())));
                        case COMPLETE -> {
                            if (event.response() == null) {
                                throw new IllegalStateException("Stream omitted its completed response");
                            }
                            current.send("complete", result(current.turn, event.response()));
                            current.completed = true;
                            return;
                        }
                        default -> {
                            /* Native replay content is committed from the COMPLETE response only. */ }
                    }
                }
                if (!current.stopped.get()) {
                    throw new IllegalStateException("Stream ended without completion");
                }
            }
        } catch (Exception failed) {
            if (!current.stopped.get()) {
                try {
                    current.send(
                            "error",
                            Map.of(
                                    "error",
                                    "Inference stream failed; history discarded. Start a new request after checking provider configuration."));
                } catch (IOException ignored) {
                    /* A disconnected client cannot receive an error event. */ }
            }
        } finally {
            current.stop();
            current.emitter.complete();
        }
    }

    private Result result(PlaygroundSessions.Turn turn, InferenceResponse response) {
        var saved = sessions.finish(turn, response);
        return new Result(
                saved.sessionId(),
                saved.revision(),
                new ResponseView(
                        response.id(),
                        response.model(),
                        response.text(),
                        response.content().stream().map(OperatorInferenceController::content).toList(),
                        response.stopReason(),
                        response.usage()),
                saved.continuationAllowed(),
                saved.historyRetained());
    }

    private static UsageLedger.Context playgroundUsage() {
        // Playground sessions are installation-local, with no registered account authority.
        return new UsageLedger.Context(null, null, null, "playground", null, null);
    }

    private final class ActiveStream {

        final PlaygroundSessions.Turn turn;
        final SseEmitter emitter;
        final AtomicBoolean stopped = new AtomicBoolean();
        final AtomicReference<OwnedStream> stream = new AtomicReference<>();
        volatile boolean completed;
        volatile ScheduledFuture<?> heartbeat;
        volatile Thread worker;

        ActiveStream(PlaygroundSessions.Turn turn, SseEmitter emitter) {
            this.turn = turn;
            this.emitter = emitter;
        }

        void send(String event, Object value) throws IOException {
            if (stopped.get()) {
                throw new IOException("Playground stream closed");
            }
            emitter.send(
                    SseEmitter.event().name(event).data(
                            PlaygroundRequests.JSON.writeValueAsString(value),
                            MediaType.APPLICATION_JSON));
        }

        void stop() {
            if (!stopped.compareAndSet(false, true)) {
                return;
            }
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
            OwnedStream owned = stream.get();
            try {
                if (owned != null) {
                    owned.close();
                }
            } finally {
                if (worker != null && worker != Thread.currentThread()) {
                    worker.interrupt();
                }
                if (!completed) {
                    sessions.discard(turn);
                }
                active.remove(this);
            }
        }
    }

    private static final class OwnedStream implements AutoCloseable {

        final InferenceStream delegate;
        final AtomicBoolean closed = new AtomicBoolean();

        OwnedStream(InferenceStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                delegate.close();
            }
        }
    }

    private static Map<String, Object> content(InferenceContent content) {
        var view = new LinkedHashMap<String, Object>();
        switch (content) {
            case InferenceContent.Text text -> {
                view.put("type", "text");
                view.put("text", text.text());
                view.put("citations", safe(text.citations()));
            }
            case InferenceContent.Thinking thinking -> {
                view.put("type", "thinking");
                view.put("text", thinking.text());
            }
            case InferenceContent.ToolCall call -> {
                view.put("type", "tool_call");
                view.put("id", call.id());
                view.put("name", call.name());
                view.put("input", safe(call.input()));
                view.put("toolset", call.toolset());
            }
            case InferenceContent.ProviderBlock block -> {
                view.put("type", "provider_block");
                view.put("provider", block.provider());
                view.put("blockType", block.type());
                view.put(
                        "data",
                        "redacted_thinking".equals(block.type())
                                ? Map.of("type", block.type())
                                : safe(block.data()));
            }
            case InferenceContent.Image image -> {
                view.put("type", "image");
                view.put("mediaType", image.mediaType());
            }
            case InferenceContent.Document document -> {
                view.put("type", "document");
                view.put("mediaType", document.mediaType());
                view.put("filename", document.filename());
            }
            case InferenceContent.ToolResult result -> {
                view.put("type", "tool_result");
                view.put("toolUseId", result.toolUseId());
                view.put("error", result.error());
            }
        }
        return view;
    }

    /** Encrypted replay state and signatures remain server-side. No SDK objects enter the HTTP contract. */
    private static Object safe(Object value) {
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.forEach((key, child) -> {
                if (!java.util.Set.of("encrypted_content", "signature", "thinking_signature")
                        .contains(String.valueOf(key))) {
                    result.put(String.valueOf(key), safe(child));
                }
            });
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(OperatorInferenceController::safe).toList();
        }
        return value;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException failure) {
        return ResponseEntity.badRequest().body(
                Map.of(
                        "error",
                        failure.getMessage() == null ? "Invalid inference request" : failure.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> status(ResponseStatusException failure) {
        return ResponseEntity.status(failure.getStatusCode()).body(
                Map.of("error", failure.getReason() == null ? "Inference unavailable" : failure.getReason()));
    }

    @PreDestroy
    public void close() {
        active.forEach(ActiveStream::stop);
        heartbeats.shutdownNow();
        workers.shutdownNow();
        sessions.clear();
    }
}
