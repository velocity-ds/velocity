package net.velocity.agent.secrets;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.mongodb.MongoWriteException;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;

/** One owner aggregate atomically commits bounded records, tombstones and immutable management receipts. */
public final class MongoCredentialRepository implements CredentialRepository {

    public static final String COLLECTION = "agent_credentials";
    public static final String AUDIT_COLLECTION = "agent_credential_audit";
    private final MongoTemplate mongo;
    private volatile boolean indexed;

    public MongoCredentialRepository(MongoTemplate mongo) {
        this.mongo = Objects.requireNonNull(mongo);
    }

    @Override
    public Optional<Vault> find(String ownerAccountId) {
        identity(ownerAccountId);
        ensureIndexes();
        Document result = mongo.execute(
                COLLECTION,
                collection -> collection.find(identity(ownerAccountId))
                        .maxTime(5000, TimeUnit.MILLISECONDS).first());
        return Optional.ofNullable(result).map(MongoCredentialRepository::decode);
    }

    @Override
    public boolean compareAndSet(Vault replacement, long expectedRevision) {
        validate(replacement);
        if (expectedRevision < 0 || replacement.revision() != expectedRevision + 1) {
            throw new IllegalArgumentException("Credential revision must increment once");
        }
        Document document = encode(replacement);
        if (document.toJson().length() > 8_000_000) {
            throw new CredentialException("capacity_exceeded");
        }
        ensureIndexes();
        try {
            return Boolean.TRUE.equals(mongo.execute(COLLECTION, collection -> {
                if (!collection.getWriteConcern().isAcknowledged()) {
                    throw new CredentialException("unavailable");
                }
                if (expectedRevision == 0) {
                    var result = collection.insertOne(document);
                    if (!result.wasAcknowledged()) {
                        throw new CredentialException("unavailable");
                    }
                    return true;
                }
                var current = collection
                        .find(identity(replacement.ownerAccountId()).append("revision", expectedRevision))
                        .first();
                if (current == null) {
                    return false;
                }
                validateTransition(decode(current), replacement);
                var result = collection.replaceOne(
                        identity(replacement.ownerAccountId()).append("revision", expectedRevision),
                        document);
                if (!result.wasAcknowledged()) {
                    throw new CredentialException("unavailable");
                }
                return result.getModifiedCount() == 1;
            }));
        } catch (DuplicateKeyException collision) {
            return false;
        } catch (MongoWriteException collision) {
            if (collision.getError().getCode() == 11000) {
                return false;
            }
            throw collision;
        }
    }

    @Override
    public void audit(Audit event) {
        ensureIndexes();
        mongo.execute(AUDIT_COLLECTION, collection -> {
            if (!collection.getWriteConcern().isAcknowledged()) {
                throw new CredentialException("unavailable");
            }
            var result = collection.insertOne(
                    new Document("_id", event.id()).append("owner_account_id", event.ownerAccountId())
                            .append("credential_id", event.credentialId())
                            .append("revision", event.revision()).append("at", event.at().toString())
                            .append("execution_id", event.executionId()).append("call_id", event.callId())
                            .append("outcome", event.outcome()));
            if (!result.wasAcknowledged()) {
                throw new CredentialException("unavailable");
            }
            return null;
        });
    }

    static Document encode(Vault vault) {
        return new Document("_id", vault.ownerAccountId()).append("owner_account_id", vault.ownerAccountId())
                .append("format", 1)
                .append("revision", vault.revision())
                .append("records", vault.records().stream().map(record -> {
                    var doc = new Document("credential_id", record.credentialId())
                            .append("owner_account_id", record.ownerAccountId())
                            .append("service", record.service())
                            .append("account_label", record.accountLabel())
                            .append("credential_origins", record.credentialOrigins())
                            .append("post_login_origins", record.postLoginOrigins())
                            .append("application_origins", record.applicationOrigins())
                            .append("callback_urls", record.callbackUrls())
                            .append("resource_origins", record.resourceOrigins())
                            .append("revision", record.revision()).append("enabled", record.enabled())
                            .append("deleted", record.deleted())
                            .append("created_at", record.createdAt().toString())
                            .append("updated_at", record.updatedAt().toString());
                    if (record.payload() != null) {
                        doc.append(
                                "payload",
                                new Document("key_id", record.payload().keyId())
                                        .append("nonce", record.payload().nonce())
                                        .append("ciphertext", record.payload().ciphertext())
                                        .append("policy_version", record.payload().policyVersion()));
                    }
                    return doc;
                }).toList()).append(
                        "receipts",
                        vault.receipts().stream()
                                .map(
                                        receipt -> new Document("request_id", receipt.requestId())
                                                .append("credential_id", receipt.credentialId())
                                                .append("revision", receipt.revision())
                                                .append("outcome", receipt.outcome())
                                                .append("at", receipt.at().toString())
                                                .append("execution_id", "local-operator")
                                                .append("call_id", receipt.requestId()))
                                .toList());
    }

    static Vault decode(Document document) {
        try {
            if (!Integer.valueOf(1).equals(document.getInteger("format"))) {
                throw new IllegalArgumentException();
            }
            String owner = document.getString("owner_account_id");
            if (!owner.equals(document.getString("_id"))) {
                throw new IllegalArgumentException();
            }
            var records = new ArrayList<CredentialRecord>();
            for (var record : document.getList("records", Document.class)) {
                var payload = record.get("payload", Document.class);
                records.add(
                        new CredentialRecord(
                                record.getString("credential_id"),
                                record.getString("owner_account_id"),
                                record.getString("service"),
                                record.getString("account_label"),
                                record.getList("credential_origins", String.class),
                                record.getList("post_login_origins", String.class),
                                optionalPolicy(record, "application_origins"),
                                optionalPolicy(record, "callback_urls"),
                                optionalPolicy(record, "resource_origins"),
                                record.getLong("revision"),
                                record.getBoolean("enabled"),
                                record.getBoolean("deleted"),
                                Instant.parse(record.getString("created_at")),
                                Instant.parse(record.getString("updated_at")),
                                payload == null
                                        ? null
                                        : new CredentialRecord.EncryptedPayload(
                                                payload.getString("key_id"),
                                                payload.getString("nonce"),
                                                payload.getString("ciphertext"),
                                                payload.getInteger("policy_version", 1))));
            }
            var receipts = document.getList("receipts", Document.class).stream()
                    .map(
                            receipt -> new Receipt(
                                    receipt.getString("request_id"),
                                    receipt.getString("credential_id"),
                                    receipt.getLong("revision"),
                                    receipt.getString("outcome"),
                                    Instant.parse(receipt.getString("at"))))
                    .toList();
            var vault = new Vault(owner, document.getLong("revision"), records, receipts);
            validate(vault);
            return vault;
        } catch (RuntimeException corrupt) {
            throw new CredentialException("unavailable");
        }
    }

    static void validate(Vault vault) {
        identity(vault.ownerAccountId());
        if (vault.revision() < 1
                || vault.records().stream().map(CredentialRecord::credentialId).distinct().count() != vault
                        .records().size()
                || vault.receipts().stream().map(Receipt::requestId).distinct().count() != vault.receipts()
                        .size()) {
            throw new CredentialException("unavailable");
        }
        for (var record : vault.records()) {
            UUID.fromString(record.credentialId());
            if (!record.ownerAccountId().equals(vault.ownerAccountId()) || record.service().isBlank()
                    || record.service().length() > 120
                    || record.accountLabel().isBlank() || record.accountLabel().length() > 120
                    || !CredentialOrigins.policy(record.credentialOrigins(), true)
                            .equals(record.credentialOrigins())
                    || !CredentialOrigins.policy(record.postLoginOrigins(), false)
                            .equals(record.postLoginOrigins())
                    || !CredentialOrigins.policy(record.applicationOrigins(), false)
                            .equals(record.applicationOrigins())
                    || !CredentialOrigins.callbacks(record.callbackUrls()).equals(record.callbackUrls())
                    || !CredentialOrigins.policy(record.resourceOrigins(), false)
                            .equals(record.resourceOrigins())) {
                throw new CredentialException("unavailable");
            }
        }
    }

    private static List<String> optionalPolicy(Document record, String key) {
        return record.containsKey(key) ? record.getList(key, String.class) : List.of();
    }

    static void validateTransition(Vault previous, Vault replacement) {
        for (var old : previous.records()) {
            var next = replacement.records().stream()
                    .filter(record -> record.credentialId().equals(old.credentialId())).findFirst()
                    .orElseThrow(() -> new CredentialException("deleted_credential"));
            if (!old.createdAt().equals(next.createdAt()) || old.deleted() && !old.equals(next)
                    || !old.equals(next) && next.revision() != old.revision() + 1) {
                throw new CredentialException("stale_credential");
            }
        }
        if (!replacement.receipts().containsAll(previous.receipts())) {
            throw new CredentialException("unavailable");
        }
    }

    private static Document identity(String owner) {
        try {
            if (!UUID.fromString(owner).toString().equals(owner)) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException invalid) {
            throw new CredentialException("invalid_request");
        }
        return new Document("_id", owner).append("owner_account_id", owner);
    }

    private void ensureIndexes() {
        if (indexed) {
            return;
        }
        synchronized (this) {
            if (indexed) {
                return;
            }
            mongo.execute(
                    COLLECTION,
                    collection -> collection.createIndexes(
                            List.of(
                                    new IndexModel(
                                            new Document("owner_account_id", 1),
                                            new IndexOptions().name("credential_owner").unique(true)))));
            mongo.execute(
                    AUDIT_COLLECTION,
                    collection -> collection.createIndexes(
                            List.of(
                                    new IndexModel(
                                            new Document("owner_account_id", 1).append("credential_id", 1)
                                                    .append("at", 1),
                                            new IndexOptions().name("credential_audit_owner")))));
            indexed = true;
        }
    }
}
