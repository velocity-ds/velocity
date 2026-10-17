package net.velocity.agent.state;

/**
 * Positive server limits. An execution may only reduce them. Time values are milliseconds.
 *
 * <p>{@code outputReserve} is the output tokens one model request may generate and reserves against the token
 * budget; {@code 0} means the pinned profile's maximum output. Only monitoring limits set it. Optional and
 * additive: limits saved before the field existed decode with {@code 0}, so format 5 still applies.
 */
public record ExecutionLimits(
        long totalTokens,
        long activeMillis,
        int modelTurns,
        int toolCalls,
        long toolTimeoutMillis,
        int outputPreviewChars,
        Long outputReserve) {

    public ExecutionLimits {
        outputReserve = outputReserve == null ? 0L : outputReserve;
        if (totalTokens < 1 || activeMillis < 1 || modelTurns < 1 || toolCalls < 1
                || toolTimeoutMillis < 1 || outputPreviewChars < 1) {
            throw new IllegalArgumentException("Execution limits must be positive");
        }
        if (outputReserve < 0) {
            throw new IllegalArgumentException("Output reserve must not be negative");
        }
    }

    public static ExecutionLimits defaults() {
        return new ExecutionLimits(470_000, 30 * 60_000L, 150, 100, 30_000, 24_576, 0L);
    }

    /** Ordinary tasks share one active-time deadline; counters remain accounting, not execution cutoffs. */
    public ExecutionLimits taskTimeOnly() {
        return new ExecutionLimits(
                Long.MAX_VALUE,
                activeMillis,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                activeMillis,
                outputPreviewChars,
                outputReserve);
    }

    public void validateWithin(ExecutionLimits server) {
        if (server == null || totalTokens > server.totalTokens || activeMillis > server.activeMillis
                || modelTurns > server.modelTurns || toolCalls > server.toolCalls
                || toolTimeoutMillis > server.toolTimeoutMillis
                || outputPreviewChars > server.outputPreviewChars
                || exceedsReserve(outputReserve, server.outputReserve)) {
            throw new IllegalArgumentException("Execution limits must not exceed server limits");
        }
    }

    /** {@code 0} is the profile maximum, the largest reserve, so only a positive server value binds. */
    private static boolean exceedsReserve(long requested, long server) {
        return server > 0 && (requested == 0 || requested > server);
    }

    public ExecutionLimits reducedTo(ExecutionLimits server) {
        validateWithin(server);
        return this;
    }

    public ExecutionLimits reduce(ExecutionLimits requested) {
        return requested == null ? this : requested.reducedTo(this);
    }
}
