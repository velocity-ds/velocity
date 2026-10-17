package net.velocity.inference;

/**
 * A provider rejected or lost a generation request for a reason a later, separately accounted
 * request may not repeat: rate limiting, overload, a server error or a dropped connection. The
 * message is a safe code; provider error bodies may echo prompts or credentials and are never kept.
 */
public final class TransientInferenceException extends IllegalStateException {

    private final String code;
    private final boolean possiblyBilled;
    private final long retryAfterMillis;

    public TransientInferenceException(String code, boolean possiblyBilled, long retryAfterMillis) {
        super(code);
        this.code = code;
        this.possiblyBilled = possiblyBilled;
        this.retryAfterMillis = Math.max(0, retryAfterMillis);
    }

    public String code() {
        return code;
    }

    /** False only when the provider answered with an error status, so no generation was charged. */
    public boolean possiblyBilled() {
        return possiblyBilled;
    }

    /** The provider's requested delay, or 0 when it gave none. */
    public long retryAfterMillis() {
        return retryAfterMillis;
    }
}
