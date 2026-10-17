package net.velocity.agent.secrets;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import net.velocity.agent.security.ReservedDestinations;
import net.velocity.agent.state.TrustedScope;

/** Account authority comes only from trusted adapters. No method accepts model-supplied ownership. */
public final class CredentialService {

    private final CredentialRepository repository;
    private final SecretsProperties properties;
    private final CredentialCrypto crypto;
    private final Clock clock;
    private final ReservedDestinations reserved;

    public CredentialService(
            CredentialRepository repository,
            SecretsProperties properties,
            CredentialCrypto crypto,
            Clock clock,
            ReservedDestinations reserved) {
        this.repository = repository;
        this.properties = properties;
        this.crypto = crypto;
        this.clock = clock;
        this.reserved = Objects.requireNonNull(reserved);
    }

    public record Availability(boolean enabled, boolean available) {}

    public record Metadata(
            String credentialId,
            long revision,
            String service,
            String accountLabel,
            List<String> credentialOrigins,
            List<String> postLoginOrigins,
            List<String> applicationOrigins,
            List<String> callbackUrls,
            List<String> resourceOrigins,
            boolean enabled,
            boolean deleted,
            Instant createdAt,
            Instant updatedAt) {}

    public record FindResult(String status, List<Metadata> credentials) {}

    public record Mutation(String status, Metadata credential, String requestId, boolean databaseCommitted) {}

    @FunctionalInterface
    public interface CredentialUse<T> {

        T use(CredentialPair pair);
    }

    public Availability availability() {
        return new Availability(properties.enabled(), properties.enabled() && crypto.available());
    }

    public List<Metadata> list(TrustedScope scope, String search) {
        String hint = search == null ? "" : search;
        if (hint.length() > 240) {
            throw new CredentialException("invalid_request");
        }
        hint = hint.toLowerCase(Locale.ROOT);
        final String query = hint;
        return vault(scope).records().stream().filter(record -> !record.deleted())
                .filter(
                        record -> (record.service() + " " + record.accountLabel()).toLowerCase(Locale.ROOT)
                                .contains(query))
                .map(CredentialService::metadata).toList();
    }

    public Metadata metadata(TrustedScope scope, String credentialId) {
        return metadata(record(scope, credentialId));
    }

    public FindResult find(TrustedScope scope, String actualOrigin, String accountHint) {
        scope.requireOwnerAccountId();
        if (!properties.enabled()) {
            return new FindResult("disabled", List.of());
        }
        if (!crypto.available()) {
            return new FindResult("unavailable", List.of());
        }
        String origin = CredentialOrigins.canonical(actualOrigin);
        String hint = accountHint == null ? "" : accountHint.trim().toLowerCase(Locale.ROOT);
        if (hint.length() > 120) {
            throw new CredentialException("invalid_request");
        }
        var matching = vault(scope).records().stream().filter(record -> !record.deleted())
                .filter(this::websiteCredentialAllowed)
                .filter(
                        record -> record.credentialOrigins().contains(origin)
                                || record.applicationOrigins().contains(origin))
                .filter(
                        record -> hint.isEmpty()
                                || record.accountLabel().trim().toLowerCase(Locale.ROOT).equals(hint))
                .toList();
        var eligible = matching.stream().filter(CredentialRecord::enabled).map(CredentialService::metadata)
                .toList();
        if (eligible.isEmpty()) {
            return new FindResult(
                    !matching.isEmpty()
                            ? "disabled_credential"
                            : hint.isEmpty() ? "no_credentials" : "account_not_found",
                    List.of());
        }
        return new FindResult(eligible.size() == 1 ? "matched" : "ambiguous", eligible);
    }

    public Metadata requireUsable(TrustedScope scope, String id, long revision) {
        enabled();
        var record = usable(scope, id, revision);
        // Configuration can be valid while lacking this record's key. Reauthenticate the exact ciphertext
        // on every step/network check, including submission of fields already filled.
        crypto.authenticate(record);
        return metadata(record);
    }

    /** Decrypt only at the final owner-thread boundary, after the caller rechecks its execution/session authority. */
    public synchronized <T> T withCredential(
            TrustedScope scope,
            String id,
            long revision,
            CredentialUse<T> use) {
        enabled();
        var record = usable(scope, id, revision);
        try (var pair = crypto.decrypt(record)) {
            return use.use(pair);
        }
    }

    private CredentialRecord usable(TrustedScope scope, String id, long revision) {
        var record = record(scope, id);
        if (record.deleted()) {
            throw new CredentialException("deleted_credential");
        }
        if (record.revision() != revision) {
            throw new CredentialException("stale_credential");
        }
        if (!record.enabled()) {
            throw new CredentialException("disabled_credential");
        }
        requireWebsiteCredential(
                record.credentialOrigins(),
                record.postLoginOrigins(),
                record.applicationOrigins(),
                record.callbackUrls(),
                record.resourceOrigins());
        return record;
    }

    public CredentialRepository.Receipt operation(TrustedScope scope, String requestId) {
        requestId(requestId);
        return vault(scope).receipts().stream().filter(receipt -> receipt.requestId().equals(requestId))
                .findFirst().orElse(null);
    }

    public synchronized Mutation create(
            TrustedScope scope,
            String requestId,
            String service,
            String label,
            List<String> origins,
            List<String> afterOrigins,
            List<String> applicationOrigins,
            List<String> callbackUrls,
            List<String> resourceOrigins,
            String username,
            String password) {
        enabled();
        requestId(requestId);
        validatePair(username, password);
        validateMetadata(service, label);
        var policy = CredentialOrigins.policy(origins, true);
        var afterPolicy = CredentialOrigins.policy(afterOrigins, false);
        var apps = CredentialOrigins.policy(applicationOrigins, false);
        var callbacks = CredentialOrigins.callbacks(callbackUrls);
        var resources = CredentialOrigins.policy(resourceOrigins, false);
        requireWebsiteCredential(policy, afterPolicy, apps, callbacks, resources);
        var vault = vault(scope);
        var existing = previous(vault, requestId);
        if (existing != null) {
            return existing;
        }
        if (vault.records().size() >= properties.maxRecords()) {
            throw new CredentialException("capacity_exceeded");
        }
        String id = UUID.randomUUID().toString();
        Instant now = clock.instant();
        CredentialRecord.EncryptedPayload payload;
        try (var pair = new CredentialPair(username, password)) {
            payload = crypto.encrypt(
                    id,
                    vault.ownerAccountId(),
                    1,
                    service,
                    label,
                    policy,
                    afterPolicy,
                    apps,
                    callbacks,
                    resources,
                    true,
                    pair);
        }
        var record = new CredentialRecord(
                id,
                vault.ownerAccountId(),
                service,
                label,
                policy,
                afterPolicy,
                apps,
                callbacks,
                resources,
                1,
                true,
                false,
                now,
                now,
                payload);
        return commit(vault, record, requestId, "created");
    }

    /** Omitted extended policies preserve the saved values for legacy API consumers. */
    public synchronized Mutation update(
            TrustedScope scope,
            String id,
            long revision,
            String requestId,
            String service,
            String label,
            List<String> origins,
            List<String> afterOrigins,
            List<String> applicationOrigins,
            List<String> callbackUrls,
            List<String> resourceOrigins,
            String username,
            String password) {
        enabled();
        requestId(requestId);
        validateMetadata(service, label);
        var policy = CredentialOrigins.policy(origins, true);
        var afterPolicy = CredentialOrigins.policy(afterOrigins, false);
        if (username != null || password != null) {
            validatePair(username, password);
        }
        var vault = vault(scope);
        var existing = previous(vault, requestId);
        if (existing != null) {
            return existing;
        }
        var record = editable(vault, id, revision);
        var apps = applicationOrigins == null
                ? record.applicationOrigins()
                : CredentialOrigins.policy(applicationOrigins, false);
        var callbacks = callbackUrls == null
                ? record.callbackUrls()
                : CredentialOrigins.callbacks(callbackUrls);
        var resources = resourceOrigins == null
                ? record.resourceOrigins()
                : CredentialOrigins.policy(resourceOrigins, false);
        requireWebsiteCredential(policy, afterPolicy, apps, callbacks, resources);
        CredentialRecord.EncryptedPayload payload;
        try (var pair = username == null ? crypto.decrypt(record) : new CredentialPair(username, password)) {
            payload = crypto.encrypt(
                    id,
                    vault.ownerAccountId(),
                    revision + 1,
                    service,
                    label,
                    policy,
                    afterPolicy,
                    apps,
                    callbacks,
                    resources,
                    record.enabled(),
                    pair);
        }
        return commit(
                vault,
                new CredentialRecord(
                        id,
                        vault.ownerAccountId(),
                        service,
                        label,
                        policy,
                        afterPolicy,
                        apps,
                        callbacks,
                        resources,
                        revision + 1,
                        record.enabled(),
                        false,
                        record.createdAt(),
                        clock.instant(),
                        payload),
                requestId,
                "updated");
    }

    public synchronized Mutation state(
            TrustedScope scope,
            String id,
            long revision,
            String requestId,
            boolean enable) {
        enabled();
        requestId(requestId);
        var vault = vault(scope);
        var existing = previous(vault, requestId);
        if (existing != null) {
            return existing;
        }
        var record = editable(vault, id, revision);
        if (enable) {
            requireWebsiteCredential(
                    record.credentialOrigins(),
                    record.postLoginOrigins(),
                    record.applicationOrigins(),
                    record.callbackUrls(),
                    record.resourceOrigins());
        }
        CredentialRecord.EncryptedPayload payload;
        try (var pair = crypto.decrypt(record)) {
            payload = crypto.encrypt(
                    id,
                    vault.ownerAccountId(),
                    revision + 1,
                    record.service(),
                    record.accountLabel(),
                    record.credentialOrigins(),
                    record.postLoginOrigins(),
                    record.applicationOrigins(),
                    record.callbackUrls(),
                    record.resourceOrigins(),
                    enable,
                    pair);
        }
        return commit(
                vault,
                new CredentialRecord(
                        id,
                        vault.ownerAccountId(),
                        record.service(),
                        record.accountLabel(),
                        record.credentialOrigins(),
                        record.postLoginOrigins(),
                        record.applicationOrigins(),
                        record.callbackUrls(),
                        record.resourceOrigins(),
                        revision + 1,
                        enable,
                        false,
                        record.createdAt(),
                        clock.instant(),
                        payload),
                requestId,
                enable ? "enabled" : "disabled");
    }

    public synchronized Mutation delete(TrustedScope scope, String id, long revision, String requestId) {
        enabled();
        requestId(requestId);
        var vault = vault(scope);
        var existing = previous(vault, requestId);
        if (existing != null) {
            return existing;
        }
        var record = editable(vault, id, revision);
        return commit(
                vault,
                new CredentialRecord(
                        id,
                        vault.ownerAccountId(),
                        record.service(),
                        record.accountLabel(),
                        record.credentialOrigins(),
                        record.postLoginOrigins(),
                        record.applicationOrigins(),
                        record.callbackUrls(),
                        record.resourceOrigins(),
                        revision + 1,
                        false,
                        true,
                        record.createdAt(),
                        clock.instant(),
                        null),
                requestId,
                "deleted");
    }

    public void auditUse(
            TrustedScope scope,
            String id,
            long revision,
            String executionId,
            String callId,
            String outcome) {
        scope.requireOwnerAccountId();
        uuid(id);
        if (executionId == null || executionId.length() > 128 || callId == null || callId.length() > 256
                || outcome == null || !outcome.matches("[a-z_]{1,64}")) {
            throw new CredentialException("invalid_request");
        }
        repository.audit(
                new CredentialRepository.Audit(
                        UUID.randomUUID().toString(),
                        scope.ownerAccountId(),
                        id,
                        revision,
                        clock.instant(),
                        executionId,
                        callId,
                        outcome));
    }

    private Mutation commit(
            CredentialRepository.Vault vault,
            CredentialRecord record,
            String requestId,
            String outcome) {
        if (vault.receipts().size() >= properties.maxReceipts()) {
            throw new CredentialException("receipt_capacity");
        }
        var records = new ArrayList<>(vault.records());
        records.removeIf(old -> old.credentialId().equals(record.credentialId()));
        records.add(record);
        var receipts = new ArrayList<>(vault.receipts());
        receipts.add(
                new CredentialRepository.Receipt(
                        requestId,
                        record.credentialId(),
                        record.revision(),
                        outcome,
                        clock.instant()));
        if (!repository.compareAndSet(
                new CredentialRepository.Vault(
                        vault.ownerAccountId(),
                        vault.revision() + 1,
                        records,
                        receipts),
                vault.revision())) {
            throw new CredentialException("revision_conflict");
        }
        return new Mutation(outcome, metadata(record), requestId, true);
    }

    private Mutation previous(CredentialRepository.Vault vault, String requestId) {
        var receipt = vault.receipts().stream().filter(value -> value.requestId().equals(requestId))
                .findFirst().orElse(null);
        if (receipt == null) {
            return null;
        }
        // The receipt is authoritative; do not resubmit or inspect plaintext to compare a repeated request.
        return new Mutation("already_committed", null, requestId, true);
    }

    private CredentialRecord editable(CredentialRepository.Vault vault, String id, long revision) {
        uuid(id);
        var record = vault.records().stream().filter(value -> value.credentialId().equals(id)).findFirst()
                .orElseThrow(() -> new CredentialException("not_found"));
        if (record.deleted()) {
            throw new CredentialException("deleted_credential");
        }
        if (revision < 1 || record.revision() != revision) {
            throw new CredentialException("revision_conflict");
        }
        return record;
    }

    private CredentialRecord record(TrustedScope scope, String id) {
        uuid(id);
        return vault(scope).records().stream().filter(record -> record.credentialId().equals(id)).findFirst()
                .orElseThrow(() -> new CredentialException("not_found"));
    }

    private CredentialRepository.Vault vault(TrustedScope scope) {
        String owner = scope.requireOwnerAccountId();
        enabled();
        return repository.find(owner)
                .orElseGet(() -> new CredentialRepository.Vault(owner, 0, List.of(), List.of()));
    }

    private void enabled() {
        if (!properties.enabled()) {
            throw new CredentialException("disabled");
        }
    }

    private static Metadata metadata(CredentialRecord record) {
        return new Metadata(
                record.credentialId(),
                record.revision(),
                record.service(),
                record.accountLabel(),
                record.credentialOrigins(),
                record.postLoginOrigins(),
                record.applicationOrigins(),
                record.callbackUrls(),
                record.resourceOrigins(),
                record.enabled(),
                record.deleted(),
                record.createdAt(),
                record.updatedAt());
    }

    private static void requestId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new CredentialException("invalid_request");
        }
    }

    private static void uuid(String value) {
        try {
            if (!UUID.fromString(value).toString().equals(value)) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException invalid) {
            throw new CredentialException("invalid_request");
        }
    }

    private static void validateMetadata(String service, String label) {
        if (service == null || service.isBlank() || service.length() > 120 || label == null || label.isBlank()
                || label.length() > 120) {
            throw new CredentialException("invalid_request");
        }
    }

    private static void validatePair(String username, String password) {
        if (username == null || username.isEmpty() || username.length() > 2048 || password == null
                || password.isEmpty() || password.length() > 4096) {
            throw new CredentialException("invalid_request");
        }
    }

    private boolean websiteCredentialAllowed(CredentialRecord record) {
        return Stream.of(
                record.credentialOrigins(),
                record.postLoginOrigins(),
                record.applicationOrigins(),
                record.callbackUrls(),
                record.resourceOrigins()).flatMap(List::stream)
                .noneMatch(reserved::credentialDestinationBlocked);
    }

    @SafeVarargs
    private void requireWebsiteCredential(List<String>... destinations) {
        if (Stream.of(destinations).flatMap(List::stream)
                .anyMatch(reserved::credentialDestinationBlocked)) {
            throw new CredentialException("reserved_destination");
        }
    }
}
