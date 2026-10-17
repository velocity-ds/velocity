package net.velocity.inference;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Carries a caller-owned monotonic deadline through one synchronous operation. */
public final class InferenceDeadlineScope implements AutoCloseable {

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();
    private final Long previous;

    private InferenceDeadlineScope(long deadlineNanos) {
        previous = CURRENT.get();
        CURRENT.set(previous == null ? deadlineNanos : earlier(previous, deadlineNanos));
    }

    /** Capture before scheduling so queue time consumes the same allowance as remote work. */
    public static long captureDeadline(long timeoutMillis) {
        long now = System.nanoTime();
        long duration = TimeUnit.MILLISECONDS.toNanos(Math.max(1, timeoutMillis));
        // Keep subtraction valid even when nanoTime wraps; application deadlines are much shorter.
        long deadline = now + Math.min(duration, Long.MAX_VALUE / 2);
        Long enclosing = CURRENT.get();
        return enclosing == null ? deadline : earlier(enclosing, deadline);
    }

    public static InferenceDeadlineScope open(long deadlineNanos) {
        return new InferenceDeadlineScope(deadlineNanos);
    }

    public static Optional<Duration> remaining() {
        Long deadline = CURRENT.get();
        // The enclosing waiter owns timeout classification and cancellation. SDKs require a
        // positive millisecond timeout: smaller values can round to an unbounded socket timeout.
        // The absolute enclosing wait still expires immediately when its deadline has elapsed.
        return deadline == null
                ? Optional.empty()
                : Optional.of(
                        Duration.ofNanos(
                                Math.max(TimeUnit.MILLISECONDS.toNanos(1), deadline - System.nanoTime())));
    }

    public static Duration remainingOr(Duration standaloneDefault) {
        return remaining().orElse(standaloneDefault);
    }

    private static long earlier(long first, long second) {
        return first - second <= 0 ? first : second;
    }

    @Override
    public void close() {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
