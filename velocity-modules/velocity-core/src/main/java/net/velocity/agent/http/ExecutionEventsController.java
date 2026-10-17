package net.velocity.agent.http;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import net.velocity.agent.AgentGateway;
import net.velocity.agent.ExecutionEvents;
import net.velocity.agent.persistence.NotFoundException;
import net.velocity.agent.state.ExecutionSnapshot;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Live task events for the console as a server-sent event stream. Every saved change of the execution is
 * pushed as a {@code snapshot} event carrying the same JSON as {@code GET /api/agent/executions/{id}};
 * streamed assistant text arrives as {@code delta} events and is retracted by {@code clear} once the saved
 * message carries it. The stream is advisory: the snapshot is authoritative and the console falls back to
 * polling when the stream is unavailable.
 */
@RestController
public final class ExecutionEventsController {

    private static final long TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(30);
    private static final long HEARTBEAT_SECONDS = 15;
    private static final long COALESCE_MILLIS = 150;
    private final AgentGateway gateway;
    private final ExecutionEvents events;
    private final ExecutorService senders = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("execution-events").factory());

    public ExecutionEventsController(AgentGateway gateway, ExecutionEvents events) {
        this.gateway = gateway;
        this.events = events;
    }

    @GetMapping(value = "/api/agent/executions/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events(@PathVariable String id, HttpServletRequest request) throws IOException {
        if (request.getQueryString() != null || request.getInputStream().read() != -1) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Execution events accept no body or query parameters");
        }
        ExecutionSnapshot current;
        try {
            current = gateway.get(id);
        } catch (NotFoundException missing) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Execution not found");
        }
        var subscription = new Subscription(id, new SseEmitter(TIMEOUT_MILLIS));
        subscription.start(current);
        return subscription.emitter;
    }

    /** One client connection: serialized sends, coalesced snapshots, and a heartbeat comment. */
    private final class Subscription implements ExecutionEvents.Listener {

        private final String id;
        private final SseEmitter emitter;
        private final AtomicLong latestRevision = new AtomicLong();
        private final AtomicLong sentRevision = new AtomicLong();
        private final AtomicBoolean scheduled = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private AutoCloseable registration;
        private ScheduledFuture<?> heartbeat;

        Subscription(String id, SseEmitter emitter) {
            this.id = id;
            this.emitter = emitter;
        }

        void start(ExecutionSnapshot current) {
            emitter.onCompletion(this::close);
            emitter.onTimeout(this::close);
            emitter.onError(ignored -> close());
            registration = events.subscribe(id, this);
            heartbeat = timers.scheduleAtFixedRate(
                    () -> senders.execute(() -> send(SseEmitter.event().comment("keep-alive"))),
                    HEARTBEAT_SECONDS,
                    HEARTBEAT_SECONDS,
                    TimeUnit.SECONDS);
            senders.execute(() -> {
                sendSnapshot(current);
                events.liveText(id).ifPresent(
                        live -> send(
                                SseEmitter.event().name("delta")
                                        .data(
                                                Map.of("modelTurn", live.modelTurn(), "text", live.text()),
                                                MediaType.APPLICATION_JSON)));
                // A write between the initial read and the subscription is picked up here.
                changed(id, current.revision);
            });
        }

        @Override
        public void changed(String executionId, long revision) {
            latestRevision.accumulateAndGet(revision, Math::max);
            if (scheduled.compareAndSet(false, true)) {
                timers.schedule(() -> senders.execute(this::flush), COALESCE_MILLIS, TimeUnit.MILLISECONDS);
            }
        }

        @Override
        public void text(String executionId, int modelTurn, String delta) {
            senders.execute(
                    () -> send(
                            SseEmitter.event().name("delta")
                                    .data(
                                            Map.of("modelTurn", modelTurn, "text", delta),
                                            MediaType.APPLICATION_JSON)));
        }

        @Override
        public void cleared(String executionId) {
            senders.execute(
                    () -> send(SseEmitter.event().name("clear").data("{}", MediaType.APPLICATION_JSON)));
        }

        private void flush() {
            scheduled.set(false);
            if (closed.get()) {
                return;
            }
            ExecutionSnapshot snapshot;
            try {
                snapshot = gateway.get(id);
            } catch (NotFoundException deleted) {
                send(SseEmitter.event().name("deleted").data("{}", MediaType.APPLICATION_JSON));
                complete();
                return;
            } catch (RuntimeException unavailable) {
                return;
            }
            if (snapshot.revision > sentRevision.get()) {
                sendSnapshot(snapshot);
            }
            if (latestRevision.get() > snapshot.revision) {
                changed(id, latestRevision.get());
            }
        }

        private void sendSnapshot(ExecutionSnapshot snapshot) {
            if (send(
                    SseEmitter.event().id(Long.toString(snapshot.revision)).name("snapshot")
                            .data(snapshot, MediaType.APPLICATION_JSON))) {
                sentRevision.accumulateAndGet(snapshot.revision, Math::max);
            }
        }

        private synchronized boolean send(SseEmitter.SseEventBuilder event) {
            if (closed.get()) {
                return false;
            }
            try {
                emitter.send(event);
                return true;
            } catch (IOException | RuntimeException gone) {
                close();
                return false;
            }
        }

        private void complete() {
            if (!closed.get()) {
                try {
                    emitter.complete();
                } catch (RuntimeException ignored) {
                    // The client is already gone.
                }
            }
            close();
        }

        private void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
            try {
                if (registration != null) {
                    registration.close();
                }
            } catch (Exception ignored) {
                // Nothing else to release.
            }
        }
    }

    @PreDestroy
    void shutdown() {
        timers.shutdownNow();
        senders.shutdownNow();
    }
}
