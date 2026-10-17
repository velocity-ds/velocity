package net.velocity.inference;

/** Safe preflight failure: remote error bodies and their potentially sensitive causes are not retained. */
public final class InputTokenCountException extends IllegalStateException {

    private final boolean transientFailure;

    public InputTokenCountException() {
        this(false);
    }

    /** A transient failure is a rate limit, overload, server error or dropped connection. */
    public InputTokenCountException(boolean transientFailure) {
        super("Provider input token counting failed");
        this.transientFailure = transientFailure;
    }

    public boolean transientFailure() {
        return transientFailure;
    }
}
