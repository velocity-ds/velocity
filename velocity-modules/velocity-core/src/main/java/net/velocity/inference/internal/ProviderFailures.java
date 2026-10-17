package net.velocity.inference.internal;

import java.io.InterruptedIOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;

import net.velocity.inference.TransientInferenceException;

/** SDK-neutral classification of provider failures that a separately accounted request may not repeat. */
public final class ProviderFailures {

    private static final Set<Integer> RETRYABLE_STATUSES = Set.of(408, 429, 500, 502, 503, 504, 529);
    private static final long MAX_RETRY_AFTER_MILLIS = 60_000;

    private ProviderFailures() {
    }

    public static boolean retryableStatus(int status) {
        return RETRYABLE_STATUSES.contains(status);
    }

    /** An error status means the provider refused the request, so no generation was charged. */
    public static TransientInferenceException status(int status, List<String> retryAfter) {
        String code = switch (status) {
            case 429 -> "provider_rate_limited";
            case 503, 529 -> "provider_overloaded";
            default -> "provider_server_error";
        };
        return new TransientInferenceException(code, false, retryAfterMillis(retryAfter));
    }

    /** A lost connection may have reached the provider, so its usage stays uncertain. */
    public static TransientInferenceException connection() {
        return new TransientInferenceException("provider_connection_failed", true, 0);
    }

    /**
     * A transport failure is transient only when it is not cancellation or a deadline: interrupts and
     * socket timeouts mean the task's own time allowance or cancellation ended the request.
     */
    public static boolean transientConnection(Throwable failure) {
        if (Thread.currentThread().isInterrupted()) {
            return false;
        }
        for (int depth = 0; failure != null && depth < 16; depth++, failure = failure.getCause()) {
            if (failure instanceof InterruptedException || failure instanceof InterruptedIOException
                    || failure instanceof CancellationException) {
                return false;
            }
        }
        return true;
    }

    /** Delta-seconds form only; an HTTP-date or malformed value falls back to the caller's backoff. */
    static long retryAfterMillis(List<String> values) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        try {
            long seconds = Long.parseLong(values.getFirst().trim());
            return seconds <= 0 ? 0 : Math.min(MAX_RETRY_AFTER_MILLIS, seconds * 1000);
        } catch (NumberFormatException | ArithmeticException malformed) {
            return 0;
        }
    }
}
