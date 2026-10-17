package net.velocity.agent.research;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.bson.BsonString;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.mongodb.WriteConcern;
import com.mongodb.client.gridfs.GridFSBucket;
import com.mongodb.client.gridfs.GridFSBuckets;
import com.mongodb.client.gridfs.model.GridFSUploadOptions;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;

import net.velocity.agent.persistence.NotFoundException;
import net.velocity.agent.state.TrustedScope;

/** Complete GridFS files are the publication/receipt boundary. No network access at construction. */
public final class MongoResearchRepository implements ResearchRepository {

    public static final String FILE_BUCKET = "agent_research_files";
    public static final String CONTROL_COLLECTION = "agent_research_controls";
    private static final WriteConcern DURABLE_WRITES = WriteConcern.MAJORITY.withJournal(true);
    private final MongoTemplate mongo;
    private volatile boolean indexed;

    public MongoResearchRepository(MongoTemplate mongo) {
        this.mongo = java.util.Objects.requireNonNull(mongo);
    }

    @Override
    public synchronized ResearchArtifact save(
            ResearchArtifact artifact,
            byte[] bytes,
            Map<String, String> privateMetadata,
            int maxArtifacts) {
        alive(artifact.executionId());
        var existing = find(artifact.id());
        if (existing.isPresent()) {
            if (!existing.get().checksum().equals(artifact.checksum())
                    || !existing.get().scope().equals(artifact.scope())) {
                throw new IllegalStateException(
                        "Research domain receipt does not match the immutable content");
            }
            return existing.get();
        }
        if (bytes.length != artifact.byteSize() || !ResearchService.hash(bytes).equals(artifact.checksum())) {
            throw new IllegalArgumentException("Research artifact bytes must match their immutable metadata");
        }
        Document metadata = new Document("artifact", encode(artifact))
                .append("executionId", artifact.executionId())
                .append("kind", artifact.kind()).append("private", new Document(privateMetadata));
        if (metadata.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 8_000_000) {
            throw new ResearchService.ResearchException(
                    "limit_reached",
                    "Artifact provenance/extraction metadata exceeds storage limit");
        }
        Document reserved = mongo.execute(
                CONTROL_COLLECTION,
                collection -> collection.withWriteConcern(DURABLE_WRITES)
                        .findOneAndUpdate(
                                new Document("_id", artifact.executionId()).append("deleted", false)
                                        .append("artifacts", new Document("$lt", maxArtifacts)),
                                new Document("$inc", new Document("artifacts", 1))
                                        .append("$addToSet", new Document("artifactIds", artifact.id())),
                                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)));
        if (reserved == null) {
            throw new ResearchService.ResearchException(
                    "limit_reached",
                    "Task artifact limit reached or execution deleted");
        }
        // A crashed upload may have persisted chunks but no published file. The durable intent above
        // owns those chunks; repairing the same immutable ID is safe before publication.
        mongo.execute(
                FILE_BUCKET + ".chunks",
                collection -> collection.withWriteConcern(DURABLE_WRITES)
                        .deleteMany(new Document("files_id", artifact.id())));
        bucket().uploadFromStream(
                new BsonString(artifact.id()),
                artifact.filename(),
                new ByteArrayInputStream(bytes),
                new GridFSUploadOptions().metadata(metadata));
        return artifact;
    }

    @Override
    public Optional<ResearchArtifact> find(String id) {
        var file = bucket().find(new Document("_id", id)).maxTime(2000, TimeUnit.MILLISECONDS).first();
        return file == null
                ? Optional.empty()
                : Optional.of(decode(file.getMetadata().get("artifact", Document.class)));
    }

    @Override
    public byte[] bytes(String id) {
        var artifact = find(id).orElseThrow(() -> new NotFoundException("Research artifact not found"));
        try (var input = bucket().openDownloadStream(new BsonString(id))) {
            if (input.getGridFSFile().getLength() != artifact.byteSize()) {
                throw new IllegalStateException("Research artifact length mismatch");
            }
            byte[] bytes = input.readAllBytes();
            if (bytes.length != artifact.byteSize()
                    || !ResearchService.hash(bytes).equals(artifact.checksum())) {
                throw new IllegalStateException("Research artifact checksum or length mismatch");
            }
            return bytes;
        } catch (IOException failure) {
            throw new IllegalStateException("Research artifact could not be read", failure);
        }
    }

    @Override
    public Map<String, String> privateMetadata(String id) {
        var file = bucket().find(new Document("_id", id)).maxTime(2000, TimeUnit.MILLISECONDS).first();
        if (file == null) {
            throw new NotFoundException("Research artifact not found");
        }
        return strings(file.getMetadata().get("private", Document.class));
    }

    @Override
    public List<ResearchArtifact> list(String executionId, String kind, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("Invalid artifact list limit");
        }
        Document query = new Document("metadata.executionId", executionId);
        if (kind != null) {
            query.append("metadata.kind", kind);
        }
        var result = new ArrayList<ResearchArtifact>();
        bucket().find(query).sort(new Document("uploadDate", -1).append("_id", 1)).limit(limit)
                .maxTime(2000, TimeUnit.MILLISECONDS)
                .forEach(file -> result.add(decode(file.getMetadata().get("artifact", Document.class))));
        return List.copyOf(result);
    }

    @Override
    public synchronized boolean reserveFetch(String executionId, int requests) {
        alive(executionId);
        return mongo.execute(
                CONTROL_COLLECTION,
                collection -> collection.withWriteConcern(DURABLE_WRITES)
                        .findOneAndUpdate(
                                new Document("_id", executionId).append("deleted", false)
                                        .append("requests", new Document("$lt", requests)),
                                new Document("$inc", new Document("requests", 1)),
                                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER))) != null;
    }

    @Override
    public synchronized void deleteExecution(String executionId) {
        mongo.execute(
                CONTROL_COLLECTION,
                collection -> collection.withWriteConcern(DURABLE_WRITES)
                        .updateOne(
                                new Document("_id", executionId),
                                new Document("$set", new Document("deleted", true)),
                                new com.mongodb.client.model.UpdateOptions().upsert(true)));
        for (var file : bucket().find(new Document("metadata.executionId", executionId))) {
            bucket().delete(file.getId());
        }
        Document state = mongo.execute(
                CONTROL_COLLECTION,
                collection -> collection.find(new Document("_id", executionId)).first());
        List<String> ids = state == null ? List.of() : state.getList("artifactIds", String.class, List.of());
        if (!ids.isEmpty()) {
            mongo.execute(
                    FILE_BUCKET + ".chunks",
                    collection -> collection.withWriteConcern(DURABLE_WRITES)
                            .deleteMany(new Document("files_id", new Document("$in", ids))));
        }
    }

    private void alive(String executionId) {
        mongo.execute(
                CONTROL_COLLECTION,
                collection -> collection.withWriteConcern(DURABLE_WRITES)
                        .updateOne(
                                new Document("_id", executionId),
                                new Document(
                                        "$setOnInsert",
                                        new Document("deleted", false)
                                                .append("requests", 0).append("artifacts", 0)),
                                new com.mongodb.client.model.UpdateOptions().upsert(true)));
        Document state = mongo.execute(
                CONTROL_COLLECTION,
                collection -> collection.find(new Document("_id", executionId))
                        .maxTime(2000, TimeUnit.MILLISECONDS).first());
        if (state == null || !Boolean.FALSE.equals(state.getBoolean("deleted"))) {
            throw new NotFoundException("Execution research was deleted");
        }
    }

    private GridFSBucket bucket() {
        if (!indexed) {
            synchronized (this) {
                if (!indexed) {
                    mongo.execute(
                            FILE_BUCKET + ".files",
                            collection -> collection.withWriteConcern(DURABLE_WRITES).createIndex(
                                    new Document("metadata.executionId", 1)
                                            .append("metadata.kind", 1).append("uploadDate", -1)
                                            .append("_id", 1)));
                    indexed = true;
                }
            }
        }
        return GridFSBuckets.create(mongo.getDb(), FILE_BUCKET).withWriteConcern(DURABLE_WRITES);
    }

    private static Document encode(ResearchArtifact item) {
        TrustedScope scope = item.scope();
        return new Document("id", item.id()).append("kind", item.kind())
                .append("executionId", item.executionId())
                .append(
                        "scope",
                        new Document("workspaceId", scope.workspaceId())
                                .append("requesterId", scope.requesterId())
                                .append("channelId", scope.channelId()).append("botId", scope.botId())
                                .append("origin", scope.origin())
                                .append("ownerAccountId", scope.requireOwnerAccountId()))
                .append("goalGeneration", item.goalGeneration()).append("filename", item.filename())
                .append("mediaType", item.mediaType())
                .append("byteSize", item.byteSize()).append("checksum", item.checksum())
                .append("createdAt", item.createdAt().toString())
                .append("evidenceIds", item.evidenceIds())
                .append("coverage", item.coverage()).append("sourceUrl", item.sourceUrl())
                .append("title", item.title())
                .append("status", item.status()).append("extractionMethod", item.extractionMethod());
    }

    private static ResearchArtifact decode(Document item) {
        Document scope = item.get("scope", Document.class);
        return new ResearchArtifact(
                item.getString("id"),
                item.getString("kind"),
                item.getString("executionId"),
                new TrustedScope(
                        scope.getString("workspaceId"),
                        scope.getString("requesterId"),
                        scope.getString("channelId"),
                        scope.getString("botId"),
                        scope.getString("origin"),
                        scope.getString("ownerAccountId")),
                item.getLong("goalGeneration"),
                item.getString("filename"),
                item.getString("mediaType"),
                item.getLong("byteSize"),
                item.getString("checksum"),
                Instant.parse(item.getString("createdAt")),
                item.getList("evidenceIds", String.class),
                item.getString("coverage"),
                item.getString("sourceUrl"),
                item.getString("title"),
                item.getString("status"),
                item.getString("extractionMethod"));
    }

    private static Map<String, String> strings(Document document) {
        Map<String, String> result = new LinkedHashMap<>();
        if (document != null) {
            document.forEach((key, value) -> result.put(key, (String) value));
        }
        return Map.copyOf(result);
    }
}
