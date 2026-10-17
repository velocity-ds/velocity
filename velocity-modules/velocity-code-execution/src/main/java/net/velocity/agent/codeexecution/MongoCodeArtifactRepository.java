package net.velocity.agent.codeexecution;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.bson.BsonString;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.gridfs.GridFSBucket;
import com.mongodb.client.gridfs.GridFSBuckets;
import com.mongodb.client.gridfs.model.GridFSFile;
import com.mongodb.client.gridfs.model.GridFSUploadOptions;
import com.mongodb.client.model.UpdateOptions;

import net.velocity.agent.persistence.ConflictException;
import net.velocity.agent.persistence.NotFoundException;

/** GridFS complete-file records publish immutable binary bytes; intents own abandoned chunks. */
public final class MongoCodeArtifactRepository implements CodeArtifactRepository {

    public static final String FILE_BUCKET = "agent_code_files";
    public static final String CONTROL_COLLECTION = "agent_code_artifact_controls";
    private final MongoTemplate mongo;
    private boolean indexed;

    public MongoCodeArtifactRepository(MongoTemplate mongo) {
        this.mongo = java.util.Objects.requireNonNull(mongo);
    }

    @Override
    public synchronized CodeArtifact save(CodeArtifact metadata, byte[] bytes) {
        alive(metadata.executionId());
        if (bytes.length != metadata.byteSize() || !CodeFiles.hash(bytes).equals(metadata.checksum())
                || !CodeFiles.mediaType(metadata.filename(), bytes).equals(metadata.mediaType())) {
            throw new IllegalArgumentException("Code artifact bytes do not match trusted metadata");
        }
        var existing = find(metadata.artifactId());
        if (existing.isPresent()) {
            if (!existing.get().equals(metadata)) {
                throw new ConflictException("Code artifact identity already contains different content");
            }
            return existing.get();
        }
        List<CodeArtifact> runFiles = new ArrayList<>();
        bucket().find(
                new Document("metadata.executionId", metadata.executionId())
                        .append("metadata.runId", metadata.runId()))
                .limit(11).forEach(file -> runFiles.add(decodeFile(file)));
        if (runFiles.size() >= 10 || runFiles.stream().mapToLong(CodeArtifact::byteSize).sum()
                + bytes.length > 25L * 1024 * 1024) {
            throw new IllegalArgumentException("Code run export limit reached");
        }
        if (runFiles.stream().anyMatch(value -> value.filename().equalsIgnoreCase(metadata.filename()))) {
            throw new ConflictException("Code run output filename already exists");
        }
        var reserved = collection(CONTROL_COLLECTION).updateOne(
                new Document("_id", metadata.executionId()).append("deleted", false),
                new Document("$addToSet", new Document("artifactIds", metadata.artifactId())));
        if (!reserved.wasAcknowledged() || reserved.getMatchedCount() != 1) {
            throw new NotFoundException("Code artifacts were deleted");
        }
        // Repair only this journaled immutable ID. A complete file above is never overwritten.
        collection(FILE_BUCKET + ".chunks").deleteMany(new Document("files_id", metadata.artifactId()));
        Document encoded = new Document("executionId", metadata.executionId())
                .append("runId", metadata.runId())
                .append("artifact", encode(metadata));
        bucket().uploadFromStream(
                new BsonString(metadata.artifactId()),
                metadata.filename(),
                new ByteArrayInputStream(bytes),
                new GridFSUploadOptions().metadata(encoded));
        return metadata;
    }

    @Override
    public Optional<CodeArtifact> find(String artifactId) {
        var file = bucket().find(new Document("_id", artifactId)).first();
        return file == null ? Optional.empty() : Optional.of(decodeFile(file));
    }

    @Override
    public byte[] bytes(String artifactId) {
        CodeArtifact metadata = find(artifactId)
                .orElseThrow(() -> new NotFoundException("Code artifact not found"));
        try (var input = bucket().openDownloadStream(new BsonString(artifactId))) {
            if (input.getGridFSFile().getLength() != metadata.byteSize()) {
                throw new IllegalStateException("Code artifact length mismatch");
            }
            byte[] bytes = input.readNBytes(Math.toIntExact(metadata.byteSize()) + 1);
            if (bytes.length != metadata.byteSize() || !CodeFiles.hash(bytes).equals(metadata.checksum())) {
                throw new IllegalStateException("Code artifact checksum or length mismatch");
            }
            return bytes;
        } catch (IOException unavailable) {
            throw new IllegalStateException("Code artifact could not be read", unavailable);
        }
    }

    @Override
    public List<CodeArtifact> listExecution(String executionId, int limit) {
        if (limit < 1 || limit > 10000) {
            throw new IllegalArgumentException("Invalid code artifact list limit");
        }
        List<CodeArtifact> results = new ArrayList<>();
        bucket().find(new Document("metadata.executionId", executionId))
                .sort(new Document("uploadDate", -1).append("_id", 1))
                .limit(limit).forEach(file -> results.add(decodeFile(file)));
        return List.copyOf(results);
    }

    @Override
    public synchronized void deleteExecution(String executionId) {
        collection(CONTROL_COLLECTION).updateOne(
                new Document("_id", executionId),
                new Document("$set", new Document("deleted", true)),
                new UpdateOptions().upsert(true));
        var control = collection(CONTROL_COLLECTION).find(new Document("_id", executionId)).first();
        List<String> ownedIds = control == null
                ? List.of()
                : control.getList("artifactIds", String.class, List.of());
        // Both file metadata and durable upload intents are exact execution-scoped ownership evidence.
        for (var file : bucket().find(new Document("metadata.executionId", executionId))) {
            bucket().delete(file.getId());
        }
        if (!ownedIds.isEmpty()) {
            collection(FILE_BUCKET + ".chunks")
                    .deleteMany(new Document("files_id", new Document("$in", ownedIds)));
        }
    }

    private void alive(String executionId) {
        collection(CONTROL_COLLECTION).updateOne(
                new Document("_id", executionId),
                new Document("$setOnInsert", new Document("deleted", false).append("artifactIds", List.of())),
                new UpdateOptions().upsert(true));
        var control = collection(CONTROL_COLLECTION).find(new Document("_id", executionId)).first();
        if (control == null || !Boolean.FALSE.equals(control.getBoolean("deleted"))) {
            throw new NotFoundException("Code artifacts were deleted");
        }
    }

    private MongoCollection<Document> collection(String name) {
        return mongo.getCollection(name).withWriteConcern(MongoCodeRunRepository.DURABLE)
                .withTimeout(5, TimeUnit.SECONDS);
    }

    private GridFSBucket bucket() {
        if (!indexed) {
            synchronized (this) {
                if (!indexed) {
                    collection(FILE_BUCKET + ".files")
                            .createIndex(new Document("metadata.executionId", 1).append("metadata.runId", 1));
                    indexed = true;
                }
            }
        }
        return GridFSBuckets.create(mongo.getDb(), FILE_BUCKET)
                .withWriteConcern(MongoCodeRunRepository.DURABLE).withTimeout(5, TimeUnit.SECONDS);
    }

    static String encode(CodeArtifact value) {
        try {
            return MongoCodeRunRepository.JSON.writeValueAsString(value);
        } catch (Exception invalid) {
            throw new IllegalStateException("Code artifact metadata is invalid", null);
        }
    }

    static CodeArtifact decode(String value) {
        try {
            return MongoCodeRunRepository.JSON.readValue(value, CodeArtifact.class);
        } catch (Exception invalid) {
            throw new IllegalStateException("Code artifact metadata is invalid", null);
        }
    }

    private static CodeArtifact decodeFile(GridFSFile file) {
        CodeArtifact value = decode(file.getMetadata().getString("artifact"));
        if (!file.getId().isString() || !file.getId().asString().getValue().equals(value.artifactId())
                || !value.executionId().equals(file.getMetadata().getString("executionId"))
                || !value.runId().equals(file.getMetadata().getString("runId"))
                || file.getLength() != value.byteSize()) {
            throw new IllegalStateException("Code artifact identity or size is invalid");
        }
        return value;
    }
}
