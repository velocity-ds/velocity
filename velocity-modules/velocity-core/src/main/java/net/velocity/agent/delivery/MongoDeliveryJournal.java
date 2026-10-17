package net.velocity.agent.delivery;

import java.util.Objects;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.UpdateOptions;

/** Durable MongoDB delivery intents, receipts and deletion tombstones, with optimistic concurrency. */
public final class MongoDeliveryJournal extends DeliveryJournal {

    public static final String DELIVERY_COLLECTION = "agent_delivery";
    public static final String SCHEDULE_COLLECTION = "agent_schedule_delivery";
    private final MongoTemplate mongo;
    private final String collectionName;

    public MongoDeliveryJournal(MongoTemplate mongo) {
        this(mongo, DELIVERY_COLLECTION);
    }

    public MongoDeliveryJournal(MongoTemplate mongo, String collectionName) {
        this.mongo = Objects.requireNonNull(mongo, "Configured MongoTemplate");
        if (!DELIVERY_COLLECTION.equals(collectionName) && !SCHEDULE_COLLECTION.equals(collectionName)) {
            throw new IllegalArgumentException("Unsupported delivery journal collection");
        }
        this.collectionName = collectionName;
    }

    @Override
    public State read(String id) {
        validateId(id);
        Document saved = collection().find(new Document("_id", id)).first();
        if (saved == null) {
            var state = new State();
            state.executionId = id;
            return state;
        }
        if (Boolean.TRUE.equals(saved.getBoolean("deleted"))) {
            throw new IllegalStateException("Delivery state was deleted");
        }
        Document payload = saved.get("state", Document.class);
        Number revision = saved.get("revision", Number.class);
        if (payload == null || revision == null || revision.longValue() < 1) {
            throw new IllegalStateException("Corrupt MongoDB delivery state");
        }
        State state = decode(payload.toJson(), id);
        state.storageRevision = revision.longValue();
        return state;
    }

    @Override
    public void save(State state) {
        Objects.requireNonNull(state, "Delivery state");
        validate(state, state.executionId);
        long expected = state.storageRevision;
        if (expected < 0 || expected == Long.MAX_VALUE) {
            throw new IllegalStateException("Invalid delivery revision");
        }
        var replacement = document(state, expected + 1);
        if (expected == 0) {
            try {
                collection().insertOne(replacement);
            } catch (MongoWriteException failure) {
                if (failure.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
                    throw new IllegalStateException("Delivery state changed or was deleted", failure);
                }
                throw failure;
            }
        } else {
            var result = collection().updateOne(
                    new Document("_id", state.executionId)
                            .append("revision", expected).append("deleted", false),
                    new Document(
                            "$set",
                            new Document("state", replacement.get("state"))
                                    .append("revision", expected + 1)));
            if (result.getModifiedCount() != 1) {
                throw new IllegalStateException("Delivery state changed or was deleted");
            }
        }
        state.storageRevision = expected + 1;
    }

    @Override
    public void delete(String id) {
        validateId(id);
        // Keep identity reserved so stale workers cannot resurrect a deleted delivery.
        collection().updateOne(
                new Document("_id", id),
                new Document("$set", new Document("deleted", true))
                        .append("$unset", new Document("state", ""))
                        .append("$inc", new Document("revision", 1L)),
                new UpdateOptions().upsert(true));
    }

    private Document document(State state, long revision) {
        return new Document("_id", state.executionId).append("revision", revision).append("deleted", false)
                .append("state", Document.parse(JSON.toJson(state)));
    }

    private MongoCollection<Document> collection() {
        // A remote send is only authorized after the intent has a durable, acknowledged write.
        return mongo.getCollection(collectionName).withWriteConcern(WriteConcern.MAJORITY.withJournal(true));
    }
}
