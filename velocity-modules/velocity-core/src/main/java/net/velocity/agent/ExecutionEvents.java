package net.velocity.agent;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Process-local fan-out of execution changes for live console views. Persisted writes publish the new
 * revision; a streaming model turn publishes assistant text as it arrives and keeps a bounded copy so a late
 * subscriber sees the text generated so far. Nothing here is durable or authoritative: MongoDB is.
 */
public final class ExecutionEvents {

    /** Live assistant text of the model turn in flight. */
    public record LiveText(int modelTurn, String text) {}

    public interface Listener {

        /** A snapshot, inbox record or receipt of the execution was saved. */
        void changed(String executionId, long revision);

        /** Assistant text arrived for the model turn in flight. */
        default void text(String executionId, int modelTurn, String delta) {
        }

        /** The in-flight text was consumed (saved, discarded or retried). */
        default void cleared(String executionId) {
        }
    }

    private static final int MAX_LIVE_CHARS = 65_536;
    private final Map<String, List<Listener>> listeners = new ConcurrentHashMap<>();
    private final Map<String, LiveText> liveText = new ConcurrentHashMap<>();

    public AutoCloseable subscribe(String executionId, Listener listener) {
        Objects.requireNonNull(executionId);
        Objects.requireNonNull(listener);
        listeners.computeIfAbsent(executionId, ignored -> new CopyOnWriteArrayList<>()).add(listener);
        return () -> listeners.computeIfPresent(executionId, (id, list) -> {
            list.remove(listener);
            return list.isEmpty() ? null : list;
        });
    }

    public void changed(String executionId, long revision) {
        for (var listener : listeners.getOrDefault(executionId, List.of())) {
            try {
                listener.changed(executionId, revision);
            } catch (RuntimeException ignored) {
                // A failing subscriber never affects the write that notified it.
            }
        }
    }

    public void text(String executionId, int modelTurn, String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        liveText.compute(executionId, (id, current) -> {
            String base = current == null || current.modelTurn() != modelTurn ? "" : current.text();
            String next = base.length() >= MAX_LIVE_CHARS ? base : base + delta;
            if (next.length() > MAX_LIVE_CHARS) {
                next = next.substring(0, MAX_LIVE_CHARS);
            }
            return new LiveText(modelTurn, next);
        });
        for (var listener : listeners.getOrDefault(executionId, List.of())) {
            try {
                listener.text(executionId, modelTurn, delta);
            } catch (RuntimeException ignored) {
                // See changed().
            }
        }
    }

    public void clear(String executionId) {
        if (liveText.remove(executionId) == null) {
            return;
        }
        for (var listener : listeners.getOrDefault(executionId, List.of())) {
            try {
                listener.cleared(executionId);
            } catch (RuntimeException ignored) {
                // See changed().
            }
        }
    }

    public Optional<LiveText> liveText(String executionId) {
        return Optional.ofNullable(liveText.get(executionId));
    }

    /** Drops every subscriber and buffer of a deleted execution. */
    public void forget(String executionId) {
        liveText.remove(executionId);
        listeners.remove(executionId);
    }
}
