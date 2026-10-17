package net.velocity.inference;

/** The request did not reach a provider and has no provider usage-ledger dispatch. */
public final class InferenceCapacityException extends IllegalStateException {

    private final String code;

    InferenceCapacityException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
