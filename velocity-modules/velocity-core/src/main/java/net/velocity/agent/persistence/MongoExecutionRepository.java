package net.velocity.agent.persistence;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.bson.BsonString;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.mongodb.WriteConcern;
import com.mongodb.client.gridfs.GridFSBucket;
import com.mongodb.client.gridfs.GridFSBuckets;
import com.mongodb.client.gridfs.model.GridFSUploadOptions;

import net.velocity.agent.delegation.DelegationProfileRegistry;
import net.velocity.agent.state.*;

import static net.velocity.agent.persistence.ExecutionStateCodec.*;

/** Mongo owns snapshots and confirmations. One process owns the active execution workers. */
public final class MongoExecutionRepository implements ExecutionRepository {

    public static final String COLLECTION = "agent_executions";
    public static final String FILE_BUCKET = "agent_execution_files";
    public static final int MAX_ARTIFACT_READ = 65_536;

    /** Told after every acknowledged write of an execution document; never before, never on failure. */
    @FunctionalInterface
    public interface ChangeListener {

        void changed(String executionId, long revision);
    }

    private final MongoTemplate mongo;
    private final ReentrantLock[] locks = new ReentrantLock[256];
    private final DelegationProfileRegistry delegationProfiles;
    private final ChangeListener changes;

    public MongoExecutionRepository(MongoTemplate mongo, DelegationProfileRegistry delegationProfiles) {
        this(mongo, delegationProfiles, (id, revision) -> {});
    }

    public MongoExecutionRepository(
            MongoTemplate mongo,
            DelegationProfileRegistry delegationProfiles,
            ChangeListener changes) {
        this.mongo = Objects.requireNonNull(mongo, "Configured MongoTemplate");
        this.delegationProfiles = Objects.requireNonNull(delegationProfiles, "Delegation profiles");
        this.changes = Objects.requireNonNull(changes, "Change listener");
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    @Override
    public ExecutionSnapshot create(ExecutionSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "Snapshot");
        String id = name(snapshot.executionId);
        return locked(id, () -> {
            ExecutionSnapshot saved = copy(snapshot);
            saved.createdAt = saved.updatedAt = Instant.now();
            saved.revision = 1;
            MessageHistory.prepare(null, saved);
            validate(saved, id, delegationProfiles);
            Document document = document(saved, List.of(), List.of());
            state(document);
            insert(document);
            return copy(saved);
        });
    }

    @Override
    public List<String> childProfileToolNames(String profile, int version) {
        return delegationProfiles.toolNames(profile, version);
    }

    @Override
    public ExecutionSnapshot get(String id) {
        return locked(name(id), () -> state(read(id)));
    }

    @Override
    public List<ExecutionSnapshot> list() {
        return mongo.execute(
                COLLECTION,
                collection -> collection.find(new Document("deleted", new Document("$ne", true)))
                        .sort(new Document("_id", 1)).map(this::state).into(new ArrayList<>()));
    }

    @Override
    public ExecutionSnapshot update(String id, long expectedRevision, Consumer<ExecutionSnapshot> mutation) {
        Objects.requireNonNull(mutation, "Mutation");
        return locked(name(id), () -> {
            Document document = read(id);
            ExecutionSnapshot before = state(document);
            revision(before, expectedRevision);
            ExecutionSnapshot after = copy(before);
            mutation.accept(after);
            if (!Objects.equals(before.executionId, after.executionId)
                    || !Objects.equals(before.originalRequest, after.originalRequest)
                    || !Objects.equals(before.scope, after.scope)
                    || !Objects.equals(before.sourceTurn, after.sourceTurn)
                    || !Objects.equals(before.monitoring, after.monitoring)
                    || !Objects.equals(before.continuedFrom, after.continuedFrom)
                    || !Objects.equals(before.createdAt, after.createdAt)) {
                throw new ConflictException("Execution identity and original request are immutable");
            }
            ExecutionStateCodec.validateInferenceTransition(before, after);
            if (before.status.terminal() && !after.status.terminal()) {
                throw new ConflictException("Terminal execution cannot resume");
            }
            validateChildTransition(before, after);
            after.limits.validateWithin(before.limits);
            after.revision = Math.addExact(before.revision, 1);
            Instant now = Instant.now();
            after.updatedAt = now.isAfter(before.updatedAt) ? now : before.updatedAt.plusNanos(1);
            MessageHistory.prepare(before, after);
            validate(after, id, delegationProfiles);
            // Gateway corrections append their durable inbox record inside the mutation.
            // Keep independently committed sidecars while still rejecting stale snapshot writes.
            Document refreshed = read(id);
            ExecutionSnapshot current = state(refreshed);
            if (current.revision != before.revision
                    || !Objects.equals(document.getString("snapshot"), refreshed.getString("snapshot"))) {
                throw new ConflictException("Execution changed concurrently");
            }
            document = refreshed;
            if ((admitsChild(before, after) || admitsInferenceBoundary(before, after))
                    && document.getList("inbox", String.class).stream()
                            .map(encoded -> decode(encoded, InboxRecord.class).correction())
                            .anyMatch(correction -> !after.consumedInboxIds.contains(correction.id()))) {
                throw new ConflictException(
                        "Pending corrections prevent child or inference boundary admission");
            }
            for (var call : after.calls) {
                if (call.state == CallState.settled
                        && !receipt(document, call.id).filter(call.settlement::equals).isPresent()) {
                    throw new ConflictException("Confirmed receipt must precede snapshot settlement");
                }
            }
            document.put("snapshot", encode(after));
            document.put("revision", after.revision);
            save(document);
            return copy(after);
        });
    }

    @Override
    public Correction inbox(String id, String text) {
        return inbox(id, text, null, List.of());
    }

    @Override
    public Correction inbox(String id, String text, SourceTurn turn, List<SourceMessage> messages) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Correction must not be blank");
        }
        List<SourceMessage> context = messages == null ? List.of() : List.copyOf(messages);
        return locked(name(id), () -> {
            Document document = read(id);
            ExecutionSnapshot snapshot = state(document);
            if (snapshot.status.terminal()) {
                throw new ConflictException("Terminal execution cannot accept corrections");
            }
            SourceMessage.validateThread(snapshot.scope, turn, context);
            Correction correction = new Correction(
                    UUID.randomUUID().toString(),
                    text,
                    Instant.now(),
                    turn,
                    context);
            document.getList("inbox", String.class).add(encode(new InboxRecord(1, id, correction)));
            save(document);
            return correction;
        });
    }

    @Override
    public List<Correction> inbox(String id) {
        return locked(name(id), () -> {
            Document document = read(id);
            state(document);
            List<Correction> result = new ArrayList<>();
            for (String encoded : document.getList("inbox", String.class)) {
                var record = decode(encoded, InboxRecord.class);
                Correction correction = record.correction();
                if (!id.equals(record.executionId()) || correction == null || correction.id() == null
                        || correction.text() == null || correction.text().isBlank()
                        || correction.createdAt() == null) {
                    throw new CorruptStateException("Invalid inbox record for " + id);
                }
                name(correction.id());
                result.add(correction);
            }
            return result.stream()
                    .sorted(Comparator.comparing(Correction::createdAt).thenComparing(Correction::id))
                    .toList();
        });
    }

    @Override
    public void receipt(String id, Settlement settlement) {
        validateSettlement(settlement);
        locked(name(id), () -> {
            Document document = read(id);
            ExecutionSnapshot snapshot = state(document);
            var call = snapshot.calls.stream().filter(item -> item.id.equals(settlement.callId())).findFirst()
                    .orElseThrow(() -> new ConflictException("Receipt has no accepted call"));
            if (!Objects.equals(call.binding, settlement.binding())) {
                throw new ConflictException("Receipt does not match accepted call binding");
            }
            Optional<Settlement> prior = receipt(document, settlement.callId());
            if (prior.isPresent()) {
                if (!prior.get().equals(settlement)) {
                    throw new ConflictException("Call already has a different receipt");
                }
                return null;
            }
            document.getList("receipts", String.class).add(encode(new ReceiptRecord(1, id, settlement)));
            save(document);
            return null;
        });
    }

    @Override
    public Optional<Settlement> receipt(String id, String callId) {
        if (callId == null || callId.isBlank()) {
            throw new IllegalArgumentException("Call ID is required");
        }
        return locked(name(id), () -> {
            Document document = read(id);
            state(document);
            return receipt(document, callId);
        });
    }

    @Override
    public String artifact(String id, String contents) {
        Objects.requireNonNull(contents, "Artifact content");
        return locked(name(id), () -> {
            state(read(id));
            String filename = UUID.randomUUID() + ".txt";
            upload(id, "artifacts/" + filename, contents);
            return filename;
        });
    }

    @Override
    public String artifact(String id, String filename, long offset, int limit) {
        if (filename == null || !filename.matches("[A-Za-z0-9_-]{1,128}\\.txt")) {
            throw new IllegalArgumentException("Invalid artifact name");
        }
        if (offset < 0 || limit < 1 || limit > MAX_ARTIFACT_READ) {
            throw new IllegalArgumentException(
                    "Artifact offset must be nonnegative and limit between 1 and " + MAX_ARTIFACT_READ);
        }
        return locked(name(id), () -> {
            state(read(id));
            BsonString key = new BsonString(id + "/artifacts/" + filename);
            GridFSBucket bucket = bucket();
            if (bucket.find(new Document("_id", key.getValue())).first() == null) {
                throw new NotFoundException("Artifact not found");
            }
            try (var reader = new InputStreamReader(bucket.openDownloadStream(key), StandardCharsets.UTF_8)) {
                long remaining = offset;
                while (remaining > 0) {
                    long skipped = reader.skip(remaining);
                    if (skipped == 0) {
                        if (reader.read() == -1) {
                            return "";
                        }
                        skipped = 1;
                    }
                    remaining -= skipped;
                }
                char[] buffer = new char[limit];
                int used = 0;
                while (used < limit) {
                    int count = reader.read(buffer, used, limit - used);
                    if (count == -1) {
                        break;
                    }
                    used += count;
                }
                return new String(buffer, 0, used);
            } catch (IOException e) {
                throw new CorruptStateException("Cannot read execution artifact", e);
            }
        });
    }

    @Override
    public void diagnostic(String id, String code, String raw) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Diagnostic code is required");
        }
        Objects.requireNonNull(raw, "Diagnostic raw output");
        locked(name(id), () -> {
            state(read(id));
            upload(
                    id,
                    "diagnostics/" + UUID.randomUUID() + ".json",
                    encode(new DiagnosticRecord(1, id, code, raw, Instant.now())));
            return null;
        });
    }

    @Override
    public void delete(String id, long expectedRevision) {
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("Deletion requires an expected revision");
        }
        locked(name(id), () -> {
            Document document = read(id);
            ExecutionSnapshot snapshot = state(document);
            revision(snapshot, expectedRevision);
            if (!snapshot.status.terminal()
                    || snapshot.calls.stream().anyMatch(call -> call.state != CallState.settled)
                    || snapshot.childRuns.values().stream().anyMatch(
                            child -> !child.status.terminal()
                                    || child.calls.stream()
                                            .anyMatch(call -> call.state != CallState.settled))) {
                throw new ConflictException("Deletion requires a settled terminal execution");
            }
            // Retain identity permanently, but remove all execution content in the same atomic write.
            Document tombstone = new Document("_id", id).append("formatVersion", 1).append("deleted", true)
                    .append("revision", snapshot.revision)
                    .append("storageRevision", document.getLong("storageRevision"))
                    .append("deletedAt", Instant.now().toString());
            save(tombstone);
            for (var file : bucket().find(new Document("metadata.executionId", id))) {
                bucket().delete(file.getId());
            }
            return null;
        });
    }

    private Document read(String id) {
        Document document = find(id);
        if (document == null || Boolean.TRUE.equals(document.get("deleted"))) {
            throw new NotFoundException("Execution not found: " + id);
        }
        return document;
    }

    private Document find(String id) {
        return mongo.execute(COLLECTION, collection -> collection.find(new Document("_id", id)).first());
    }

    private ExecutionSnapshot state(Document document) {
        validateDocumentSize(document);
        if (!Integer.valueOf(1).equals(document.get("formatVersion"))
                || !(document.get("_id") instanceof String)
                || !Boolean.FALSE.equals(document.get("deleted"))
                || !(document.get("storageRevision") instanceof Long version) || version < 1
                || !(document.get("revision") instanceof Long revision) || revision < 1
                || !(document.get("snapshot") instanceof String)
                || !(document.get("inbox") instanceof List<?> inbox)
                || inbox.stream().anyMatch(item -> !(item instanceof String))
                || !(document.get("receipts") instanceof List<?> receipts)
                || receipts.stream().anyMatch(item -> !(item instanceof String))) {
            throw new CorruptStateException("Unsupported or incomplete execution document");
        }
        ExecutionSnapshot result = decode(document.getString("snapshot"), ExecutionSnapshot.class);
        validate(result, document.getString("_id"), delegationProfiles);
        if (!Long.valueOf(result.revision).equals(document.getLong("revision"))) {
            throw new CorruptStateException("Execution revision does not match snapshot");
        }
        for (var call : result.calls) {
            if (call.state == CallState.settled
                    && !receipt(document, call.id).filter(call.settlement::equals).isPresent()) {
                throw new CorruptStateException(
                        "Settled call is missing its matching confirmed receipt: " + result.executionId);
            }
        }
        return result;
    }

    private Optional<Settlement> receipt(Document document, String callId) {
        Settlement found = null;
        for (String encoded : document.getList("receipts", String.class)) {
            var record = decode(encoded, ReceiptRecord.class);
            validateSettlement(record.settlement());
            if (!document.getString("_id").equals(record.executionId())) {
                throw new CorruptStateException("Receipt identity mismatch");
            }
            if (record.settlement().callId().equals(callId)) {
                if (found != null) {
                    throw new CorruptStateException("Duplicate settlement receipt");
                }
                found = record.settlement();
            }
        }
        return Optional.ofNullable(found);
    }

    private static Document document(ExecutionSnapshot snapshot, List<String> inbox, List<String> receipts) {
        var document = new Document("_id", snapshot.executionId).append("formatVersion", 1)
                .append("deleted", false)
                .append("revision", snapshot.revision).append("storageRevision", 1L)
                .append("snapshot", encode(snapshot))
                .append("inbox", new ArrayList<>(inbox)).append("receipts", new ArrayList<>(receipts));
        // Queryable copy of the immutable link; updates keep it and a deletion tombstone drops it.
        if (snapshot.continuedFrom != null) {
            document.append("continuedFrom", snapshot.continuedFrom);
        }
        return document;
    }

    @Override
    public Optional<String> continuationOf(String sourceExecutionId) {
        Objects.requireNonNull(sourceExecutionId, "Source execution ID");
        return Optional.ofNullable(
                mongo.execute(
                        COLLECTION,
                        collection -> collection
                                .find(
                                        new Document("continuedFrom", sourceExecutionId)
                                                .append("deleted", false))
                                .projection(new Document("_id", 1)).sort(new Document("_id", 1)).first()))
                .map(document -> document.getString("_id"));
    }

    private void insert(Document document) {
        validateDocumentSize(document);
        try {
            mongo.execute(COLLECTION, collection -> {
                if (!collection.getWriteConcern().isAcknowledged()) {
                    throw new IllegalStateException("Execution writes require acknowledgement");
                }
                collection.withWriteConcern(WriteConcern.MAJORITY.withJournal(true)).insertOne(document);
                return null;
            });
        } catch (DuplicateKeyException e) {
            throw new ConflictException(
                    "Execution already exists or was deleted: " + document.getString("_id"));
        }
        notifyChanged(document);
    }

    private void notifyChanged(Document document) {
        Object revision = document.get("revision");
        changes.changed(
                document.getString("_id"),
                revision instanceof Number number ? number.longValue() : 0);
    }

    private void save(Document document) {
        long version = document.getLong("storageRevision");
        document.put("storageRevision", Math.addExact(version, 1));
        validateDocumentSize(document);
        mongo.execute(COLLECTION, collection -> {
            if (!collection.getWriteConcern().isAcknowledged()) {
                throw new IllegalStateException("Execution writes require acknowledgement");
            }
            var result = collection.withWriteConcern(WriteConcern.MAJORITY.withJournal(true)).replaceOne(
                    new Document("_id", document.getString("_id"))
                            .append("storageRevision", version).append("deleted", false),
                    document);
            if (result.getModifiedCount() != 1) {
                throw new ConflictException("Execution changed concurrently");
            }
            return null;
        });
        notifyChanged(document);
    }

    private GridFSBucket bucket() {
        GridFSBucket bucket = GridFSBuckets.create(mongo.getDb(), FILE_BUCKET);
        if (!bucket.getWriteConcern().isAcknowledged()) {
            throw new IllegalStateException("Execution files require acknowledgement");
        }
        return bucket.withWriteConcern(WriteConcern.MAJORITY.withJournal(true));
    }

    private void upload(String id, String filename, String content) {
        bucket().uploadFromStream(
                new BsonString(id + "/" + filename),
                filename,
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
                new GridFSUploadOptions().metadata(new Document("executionId", id)));
    }

    private <T> T locked(String id, Supplier<T> action) {
        ReentrantLock lock = locks[(id.hashCode() & Integer.MAX_VALUE) % locks.length];
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

}
