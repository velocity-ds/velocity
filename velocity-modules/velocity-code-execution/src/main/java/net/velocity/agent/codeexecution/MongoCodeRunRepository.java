package net.velocity.agent.codeexecution;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mongodb.MongoWriteException;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.UpdateOptions;

import net.velocity.agent.persistence.ConflictException;
import net.velocity.agent.persistence.NotFoundException;

/** Single-installation optimistic journal, with acknowledged intents before container dispatch. */
public final class MongoCodeRunRepository implements CodeRunRepository {

    public static final String COLLECTION = "agent_code_runs";
    public static final String CONTROL_COLLECTION = "agent_code_controls";
    static final WriteConcern DURABLE = WriteConcern.MAJORITY.withJournal(true)
            .withWTimeout(5, TimeUnit.SECONDS);
    static final JsonMapper JSON = JsonMapper.builder().findAndAddModules()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final MongoTemplate mongo;
    private boolean indexed;

    public MongoCodeRunRepository(MongoTemplate mongo) {
        this.mongo = java.util.Objects.requireNonNull(mongo);
    }

    @Override
    public Optional<Run> find(String runId) {
        return Optional.ofNullable(collection(COLLECTION).find(new Document("_id", runId)).first())
                .map(MongoCodeRunRepository::decode);
    }

    @Override
    public synchronized boolean insert(Run admitted) {
        if (admitted.revision() != 1 || !admitted.state().equals("admitted")
                || admitted.receiptJson() != null) {
            throw new IllegalArgumentException("Code dispatch must begin with an admitted intent");
        }
        if (deleted(admitted.executionId())) {
            throw new NotFoundException("Code execution was deleted");
        }
        indexes();
        try {
            return collection(COLLECTION).insertOne(encode(admitted)).wasAcknowledged();
        } catch (DuplicateKeyException duplicate) {
            return false;
        } catch (MongoWriteException duplicate) {
            if (duplicate.getError().getCode() == 11000) {
                return false;
            }
            throw duplicate;
        }
    }

    @Override
    public synchronized boolean compareAndSet(Run replacement, long expectedRevision) {
        if (replacement.revision() != expectedRevision + 1) {
            throw new IllegalArgumentException("Invalid code run revision");
        }
        if (deleted(replacement.executionId())) {
            throw new NotFoundException("Code execution was deleted");
        }
        Run previous = find(replacement.runId())
                .orElseThrow(() -> new NotFoundException("Code run not found"));
        if (!previous.validReplacement(replacement)) {
            return false;
        }
        var result = collection(COLLECTION).replaceOne(
                new Document("_id", replacement.runId()).append("revision", expectedRevision),
                encode(replacement));
        return result.wasAcknowledged() && result.getModifiedCount() == 1;
    }

    @Override
    public List<Run> listExecution(String executionId, int limit) {
        limit(limit);
        return collection(COLLECTION).find(new Document("executionId", executionId))
                .sort(new Document("createdAt", -1).append("_id", 1))
                .limit(limit).map(MongoCodeRunRepository::decode).into(new ArrayList<>());
    }

    @Override
    public List<Run> recoverable(int limit) {
        limit(limit);
        return collection(COLLECTION)
                .find(
                        new Document(
                                "$or",
                                List.of(
                                        new Document("state", new Document("$ne", "terminal")),
                                        new Document("cleanupPending", true))))
                .sort(new Document("createdAt", 1).append("_id", 1)).limit(limit)
                .map(MongoCodeRunRepository::decode).into(new ArrayList<>());
    }

    @Override
    public synchronized void deleteExecution(String executionId) {
        if (collection(COLLECTION).find(
                new Document("executionId", executionId).append(
                        "$or",
                        List.of(
                                new Document("state", new Document("$ne", "terminal")),
                                new Document("cleanupPending", true))))
                .first() != null) {
            throw new ConflictException("Code workers or cleanup are not confirmed settled");
        }
        collection(CONTROL_COLLECTION).updateOne(
                new Document("_id", executionId),
                new Document("$set", new Document("deleted", true)),
                new UpdateOptions().upsert(true));
        collection(COLLECTION).deleteMany(new Document("executionId", executionId));
    }

    @Override
    public boolean deleted(String executionId) {
        return collection(CONTROL_COLLECTION).find(new Document("_id", executionId).append("deleted", true))
                .first() != null;
    }

    private MongoCollection<Document> collection(String name) {
        return mongo.getCollection(name).withWriteConcern(DURABLE).withTimeout(5, TimeUnit.SECONDS);
    }

    private void indexes() {
        if (indexed) {
            return;
        }
        collection(COLLECTION).createIndex(
                new Document("ownerAccountId", 1).append("executionId", 1).append("acceptedCallId", 1),
                new IndexOptions().unique(true));
        collection(COLLECTION).createIndex(new Document("resourceName", 1), new IndexOptions().unique(true));
        collection(COLLECTION).createIndex(new Document("state", 1).append("cleanupPending", 1));
        indexed = true;
    }

    static Document encode(Run run) {
        try {
            return new Document("_id", run.runId()).append("ownerAccountId", run.ownerAccountId())
                    .append("executionId", run.executionId()).append("acceptedCallId", run.acceptedCallId())
                    .append("resourceName", run.resourceName()).append("state", run.state())
                    .append("cleanupPending", run.cleanupPending())
                    .append("revision", run.revision()).append("createdAt", run.createdAt().toString())
                    .append("format", 1).append("snapshot", JSON.writeValueAsString(run));
        } catch (Exception unavailable) {
            throw new IllegalStateException("Code journal serialization failed", null);
        }
    }

    static Run decode(Document document) {
        try {
            Run run = JSON.readValue(document.getString("snapshot"), Run.class);
            if (!encode(run).equals(document)) {
                throw new IllegalArgumentException();
            }
            return run;
        } catch (Exception unavailable) {
            throw new IllegalStateException("Code journal is invalid", null);
        }
    }

    private static void limit(int limit) {
        if (limit < 1 || limit > 10000) {
            throw new IllegalArgumentException("Invalid code run list limit");
        }
    }
}
