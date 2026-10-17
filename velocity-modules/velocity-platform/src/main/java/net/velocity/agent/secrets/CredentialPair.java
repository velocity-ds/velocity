package net.velocity.agent.secrets;

import java.util.Arrays;

/** Short-lived plaintext. Consumers must not retain strings or include this object in diagnostics. */
public final class CredentialPair implements AutoCloseable {

    private char[] username;
    private char[] password;

    public CredentialPair(String username, String password) {
        this.username = username.toCharArray();
        this.password = password.toCharArray();
    }

    public String username() {
        checkOpen();
        return new String(username);
    }

    public String password() {
        checkOpen();
        return new String(password);
    }

    private void checkOpen() {
        if (username == null) {
            throw new CredentialException("credential_closed");
        }
    }

    @Override
    public void close() {
        if (username != null) {
            Arrays.fill(username, '\0');
            Arrays.fill(password, '\0');
            username = null;
            password = null;
        }
    }

    @Override
    public String toString() {
        return "CredentialPair[REDACTED]";
    }
}
