package net.velocity.agent.persistence;

public final class CorruptStateException extends RuntimeException {

    public CorruptStateException(String message) {
        super(message);
    }

    public CorruptStateException(String message, Throwable cause) {
        super(message, cause);
    }
}
