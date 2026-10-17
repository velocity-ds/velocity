package net.velocity.agent.skills;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mongodb.MongoWriteException;

/** Bounded owner documents need only MongoDB's built-in unique _id index. */
public final class MongoSkillRepository implements SkillRepository {

    public static final String COLLECTION = "agent_skills";
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_LONG_FOR_INTS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).build();
    private final MongoTemplate mongo;

    public MongoSkillRepository(MongoTemplate mongo) {
        this.mongo = java.util.Objects.requireNonNull(mongo);
    }

    @Override
    public Optional<SkillStore> find(String ownerAccountId) {
        checkOwner(ownerAccountId);
        Document document = mongo.execute(
                COLLECTION,
                collection -> collection.find(new Document("_id", ownerAccountId))
                        .maxTime(2000, TimeUnit.MILLISECONDS).first());
        if (document == null) {
            return Optional.empty();
        }
        try {
            SkillStore store = upgrade(JSON.readValue(document.getString("state"), SkillStore.class));
            SkillService.validateStore(store);
            if (!ownerAccountId.equals(document.getString("owner_account_id"))
                    || !ownerAccountId.equals(store.ownerAccountId())
                    || store.revision() != ((Number) document.get("revision")).longValue()) {
                throw new IllegalStateException("Skill ownership/revision mismatch");
            }
            return Optional.of(store);
        } catch (Exception invalid) {
            throw new IllegalStateException("Corrupt skill store", invalid);
        }
    }

    @Override
    public boolean compareAndSet(SkillStore replacement, long expectedRevision) {
        SkillService.validateStore(replacement);
        if (expectedRevision < 0 || replacement.revision() != expectedRevision + 1) {
            throw new IllegalArgumentException("Skill replacement must advance revision");
        }
        final String state;
        try {
            state = JSON.writeValueAsString(replacement);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid skill state", invalid);
        }
        if (state.getBytes(StandardCharsets.UTF_8).length > 8_000_000) {
            throw new IllegalArgumentException("Skill store exceeds storage capacity");
        }
        Document document = new Document("_id", replacement.ownerAccountId())
                .append("owner_account_id", replacement.ownerAccountId())
                .append("revision", replacement.revision()).append("state", state);
        return Boolean.TRUE.equals(mongo.execute(COLLECTION, collection -> {
            if (!collection.getWriteConcern().isAcknowledged()) {
                throw new IllegalStateException("Skill writes require acknowledgement");
            }
            if (expectedRevision == 0) {
                try {
                    return collection.insertOne(document).wasAcknowledged();
                } catch (MongoWriteException conflict) {
                    if (conflict.getError().getCode() == 11000) {
                        return false;
                    }
                    throw conflict;
                }
            }
            var result = collection.replaceOne(
                    new Document("_id", replacement.ownerAccountId())
                            .append("owner_account_id", replacement.ownerAccountId())
                            .append("revision", expectedRevision),
                    document);
            if (!result.wasAcknowledged()) {
                throw new IllegalStateException("Skill writes require acknowledgement");
            }
            return result.getModifiedCount() == 1;
        }));
    }

    /** Format 1 stores carry no usage; they decode with empty usage and are rewritten by the next change. */
    private static SkillStore upgrade(SkillStore store) {
        if (store.formatVersion() != 1) {
            return store;
        }
        return new SkillStore(
                SkillStore.FORMAT_VERSION,
                store.ownerAccountId(),
                store.revision(),
                store.policyGeneration(),
                store.skills().stream().map(skill -> skill.withUsage(SkillStore.Usage.none())).toList(),
                store.receipts(),
                List.of());
    }

    private static void checkOwner(String owner) {
        if (owner == null || !owner.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) {
            throw new IllegalArgumentException("Invalid skill owner");
        }
    }
}
