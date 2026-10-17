package net.velocity.agent.secrets;

/** Fixed nonsecret outcomes. Never attach an exception from a crypto, request or browser boundary. */
public final class CredentialException extends RuntimeException {

    private final String status;

    public CredentialException(String status) {
        super("Credential operation: " + status);
        this.status = status;
    }

    public String status() {
        return status;
    }
}
