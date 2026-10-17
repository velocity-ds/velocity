package net.velocity.agent;

import java.util.Objects;
import java.util.function.ToDoubleFunction;

import net.velocity.agent.state.ExecutionStatus;
import net.velocity.inference.objects.InferenceResponse;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;

/**
 * Operational counters and gauges on Micrometer's global registry, which Spring Boot binds to the configured
 * registries. Tags are bounded vocabularies (tool and provider names, statuses, outcome codes), never user
 * text or identifiers. Recording never throws: metrics are observation, not control flow.
 */
public final class AgentMetrics {

    private AgentMetrics() {
    }

    public static void taskAdmitted(String origin, boolean monitoring) {
        count(
                "velocity.tasks.admitted",
                Tags.of("origin", safe(origin), "monitoring", Boolean.toString(monitoring)));
    }

    public static void taskStopped(ExecutionStatus status, boolean root) {
        count("velocity.tasks.stopped", Tags.of("status", safe(status), "root", Boolean.toString(root)));
    }

    public static void toolCall(String tool, String outcome, boolean root) {
        count(
                "velocity.tool.calls",
                Tags.of("tool", safe(tool), "outcome", safe(outcome), "root", Boolean.toString(root)));
    }

    public static void toolDuration(String tool, long millis) {
        try {
            Metrics.timer("velocity.tool.duration", Tags.of("tool", safe(tool)))
                    .record(java.time.Duration.ofMillis(Math.max(0, millis)));
        } catch (RuntimeException ignored) {
            // See class comment.
        }
    }

    public static void inferenceRequest(String provider, String model, String outcome, long millis) {
        try {
            Metrics.timer(
                    "velocity.inference.requests",
                    Tags.of("provider", safe(provider), "model", safe(model), "outcome", safe(outcome)))
                    .record(java.time.Duration.ofMillis(Math.max(0, millis)));
        } catch (RuntimeException ignored) {
            // See class comment.
        }
    }

    public static void inferenceTokens(String provider, String model, InferenceResponse.Usage usage) {
        if (usage == null) {
            return;
        }
        tokens(provider, model, "input", usage.inputTokens());
        tokens(provider, model, "output", usage.outputTokens());
        tokens(provider, model, "cache_read", usage.cacheReadTokens());
        tokens(provider, model, "cache_creation", usage.cacheCreationTokens());
    }

    /** Registers a gauge over {@code source}; Micrometer holds it weakly, so the caller keeps the reference. */
    public static <T> void gauge(String name, T source, ToDoubleFunction<T> value) {
        try {
            Metrics.gauge(name, source, value);
        } catch (RuntimeException ignored) {
            // See class comment.
        }
    }

    private static void tokens(String provider, String model, String kind, long count) {
        if (count <= 0) {
            return;
        }
        try {
            Metrics.counter(
                    "velocity.inference.tokens",
                    Tags.of("provider", safe(provider), "model", safe(model), "kind", kind))
                    .increment(count);
        } catch (RuntimeException ignored) {
            // See class comment.
        }
    }

    private static void count(String name, Tags tags) {
        try {
            Metrics.counter(name, tags).increment();
        } catch (RuntimeException ignored) {
            // See class comment.
        }
    }

    private static String safe(Object value) {
        String text = Objects.toString(value, "unknown");
        return text.isBlank() ? "unknown" : text.length() > 64 ? text.substring(0, 64) : text;
    }
}
