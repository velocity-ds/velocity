package net.velocity.agent.monitoring;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import net.velocity.agent.state.ExecutionLimits;

/**
 * Installation limits; monitoring requests cannot increase these budgets. {@code outputReserve} caps the
 * output of each model request in a check and sizes the final-report reserve; the pinned profile's maximum
 * output bounds it.
 */
@ConfigurationProperties("agent.monitoring")
public record MonitoringProperties(
        int maximumActiveJobs,
        int concurrentRuns,
        Duration minimumCadence,
        int toolCalls,
        int results,
        Duration runDuration,
        long totalTokens,
        long outputReserve,
        int modelTurns,
        int notificationChars,
        Duration retention,
        int retainedRuns) {

    private static final String PREFIX = "agent.monitoring.";

    public MonitoringProperties {
        atLeast(maximumActiveJobs, 1, "maximum-active-jobs");
        atLeast(concurrentRuns, 1, "concurrent-runs");
        atLeast(toolCalls, 1, "tool-calls");
        atLeast(results, 1, "results");
        atLeast(modelTurns, 1, "model-turns");
        atLeast(totalTokens, 1, "total-tokens");
        atLeast(outputReserve, 1, "output-reserve");
        atLeast(notificationChars, 1000, "notification-chars");
        atLeast(retainedRuns, 1, "retained-runs");
        atLeast(minimumCadence, Duration.ofMinutes(1), "minimum-cadence");
        atLeast(runDuration, Duration.ofSeconds(1), "run-duration");
        atLeast(retention, Duration.ofDays(1), "retention");
    }

    private static void atLeast(long value, long minimum, String name) {
        if (value < minimum) {
            throw new IllegalArgumentException(
                    PREFIX + name + " must be at least " + minimum + ", was " + value);
        }
    }

    private static void atLeast(Duration value, Duration minimum, String name) {
        if (value == null) {
            throw new IllegalArgumentException(PREFIX + name + " is required");
        }
        if (value.compareTo(minimum) < 0) {
            throw new IllegalArgumentException(
                    PREFIX + name + " must be at least " + minimum + ", was " + value);
        }
    }

    public ExecutionLimits executionLimits() {
        return new ExecutionLimits(
                totalTokens,
                runDuration.toMillis(),
                modelTurns,
                toolCalls,
                Math.min(30000, runDuration.toMillis()),
                24576,
                outputReserve);
    }
}
