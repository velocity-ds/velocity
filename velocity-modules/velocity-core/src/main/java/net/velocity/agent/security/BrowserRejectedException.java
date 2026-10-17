package net.velocity.agent.security;

import java.util.Map;

/** A recoverable browser rejection, used only before an interaction has started. */
public final class BrowserRejectedException extends RuntimeException {

    private final String code;
    private final Map<String, String> sessionBinding;

    public BrowserRejectedException(String code, String message) {
        super(message);
        this.code = code;
        this.sessionBinding = Map.of();
    }

    /** Only trusted session ownership checks may supply a current binding. */
    public BrowserRejectedException(String code, String message, String sessionId, String generation) {
        super(message);
        this.code = code;
        this.sessionBinding = Map.of("session_id", sessionId, "generation", generation);
    }

    public String code() {
        return code;
    }

    public Map<String, String> sessionBinding() {
        return sessionBinding;
    }
}
