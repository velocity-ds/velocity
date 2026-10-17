package net.velocity.inference.objects;

/** Preserves observable provider usage even when the returned response cannot be accepted. */
public final class InferenceResponseException extends IllegalStateException {

    private final InferenceResponse.Usage usage;
    private final boolean malformed;
    private final String model;

    public InferenceResponseException(
            String message,
            Throwable cause,
            InferenceResponse.Usage usage,
            boolean malformed) {
        this(message, cause, usage, malformed, null);
    }

    public InferenceResponseException(
            String message,
            Throwable cause,
            InferenceResponse.Usage usage,
            boolean malformed,
            String model) {
        super(message, cause);
        this.usage = usage;
        this.malformed = malformed;
        this.model = model;
    }

    public InferenceResponse.Usage usage() {
        return usage;
    }

    public boolean malformed() {
        return malformed;
    }

    public String model() {
        return model;
    }
}
