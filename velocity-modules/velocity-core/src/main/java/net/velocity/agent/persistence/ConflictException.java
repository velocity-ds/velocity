package net.velocity.agent.persistence;

public final class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
