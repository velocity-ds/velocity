package net.velocity.agent.secrets;

import java.time.Instant;
import java.util.List;

public record CredentialRecord(
        String credentialId,
        String ownerAccountId,
        String service,
        String accountLabel,
        List<String> credentialOrigins,
        List<String> postLoginOrigins,
        List<String> applicationOrigins,
        List<String> callbackUrls,
        List<String> resourceOrigins,
        long revision,
        boolean enabled,
        boolean deleted,
        Instant createdAt,
        Instant updatedAt,
        EncryptedPayload payload) {

    public CredentialRecord {
        credentialOrigins = List.copyOf(credentialOrigins);
        postLoginOrigins = List.copyOf(postLoginOrigins);
        applicationOrigins = List.copyOf(applicationOrigins);
        callbackUrls = List.copyOf(callbackUrls);
        resourceOrigins = List.copyOf(resourceOrigins);
        if (revision < 1 || deleted && (enabled || payload != null) || !deleted && payload == null) {
            throw new IllegalArgumentException("Invalid credential state");
        }
    }

    public record EncryptedPayload(String keyId, String nonce, String ciphertext, int policyVersion) {

        @Override
        public String toString() {
            return "EncryptedPayload[keyId=" + keyId + "]";
        }
    }
}
