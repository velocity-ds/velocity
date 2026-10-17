package net.velocity.agent.secrets;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CredentialRepository {

    Optional<Vault> find(String ownerAccountId);

    boolean compareAndSet(Vault replacement, long expectedRevision);

    void audit(Audit event);

    record Vault(
            String ownerAccountId,
            long revision,
            List<CredentialRecord> records,
            List<Receipt> receipts) {

        public Vault {
            records = List.copyOf(records);
            receipts = List.copyOf(receipts);
        }
    }

    record Receipt(String requestId, String credentialId, long revision, String outcome, Instant at) {}

    record Audit(
            String id,
            String ownerAccountId,
            String credentialId,
            long revision,
            Instant at,
            String executionId,
            String callId,
            String outcome) {}
}
