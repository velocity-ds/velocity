package net.velocity.agent.memory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.mongodb.MongoWriteException;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;

public final class MongoMemoryRepository implements MemoryRepository {

    public static final String COLLECTION = "agent_memory_stores";
    private final MongoTemplate mongo;
    private volatile boolean indexed;

    public MongoMemoryRepository(MongoTemplate mongo) {
        this.mongo = Objects.requireNonNull(mongo);
    }

    @Override
    public Optional<MemoryStore> find(String ownerAccountId, String target) {
        ensureIndexes();
        Document found = mongo.execute(
                COLLECTION,
                collection -> collection.find(identity(ownerAccountId, target))
                        .maxTime(5000, TimeUnit.MILLISECONDS).first());
        return Optional.ofNullable(found).map(MemoryCodec::decode);
    }

    @Override
    public boolean compareAndSet(MemoryStore replacement, long expectedRevision) {
        MemoryService.validateStore(replacement);
        if (expectedRevision < 0 || replacement.revision() != expectedRevision + 1) {
            throw new IllegalArgumentException("Memory revision must increment once");
        }
        if (MemoryCodec.size(replacement) > 8_000_000) {
            throw new IllegalArgumentException("Memory aggregate capacity exceeded");
        }
        ensureIndexes();
        try {
            return Boolean.TRUE.equals(mongo.execute(COLLECTION, collection -> {
                if (!collection.getWriteConcern().isAcknowledged()) {
                    throw new IllegalStateException("Memory writes require acknowledgement");
                }
                if (expectedRevision == 0) {
                    var result = collection.insertOne(MemoryCodec.encode(replacement));
                    if (!result.wasAcknowledged()) {
                        throw new IllegalStateException("Memory write was not acknowledged");
                    }
                    return true;
                }
                var result = collection.replaceOne(
                        identity(replacement.ownerAccountId(), replacement.target())
                                .append("revision", expectedRevision),
                        MemoryCodec.encode(replacement));
                if (!result.wasAcknowledged()) {
                    throw new IllegalStateException("Memory write was not acknowledged");
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

    private static Document identity(String ownerAccountId, String target) {
        if (ownerAccountId == null
                || !ownerAccountId.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
                || !List.of("user", "environment", "profile").contains(target)) {
            throw new IllegalArgumentException("Invalid memory identity");
        }
        return new Document("_id", ownerAccountId + ":" + target).append("owner_account_id", ownerAccountId)
                .append("target", target);
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
                                            new Document("owner_account_id", 1).append("target", 1),
                                            new IndexOptions().name("memory_owner_target").unique(true)))));
            indexed = true;
        }
    }
}
