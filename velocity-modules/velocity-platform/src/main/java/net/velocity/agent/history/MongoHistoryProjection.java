package net.velocity.agent.history;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.ReplaceOptions;

/** Each execution's bounded projection is replaced atomically; deletion tombstones prohibit late recreation. */
public final class MongoHistoryProjection implements HistoryProjection {

    public static final String COLLECTION = "agent_history_messages";
    private final MongoTemplate mongo;
    private volatile boolean indexed;

    public MongoHistoryProjection(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    private synchronized void indexes() {
        if (indexed) {
            return;
        }
        mongo.getCollection(COLLECTION).createIndex(
                new Document("owner_account_id", 1).append("messages.text", "text"),
                new IndexOptions().name("owner_message_text"));
        indexed = true;
    }

    @Override
    public long generation(String id) {
        indexes();
        Document value = mongo.getCollection(COLLECTION).find(new Document("_id", id)).first();
        return value == null
                ? -1
                : value.getBoolean("deleted", false) ? Long.MAX_VALUE : value.getLong("generation");
    }

    @Override
    public void replace(String id, long generation, List<Message> messages) {
        indexes();
        var docs = messages.stream().map(
                message -> new Document("messageId", message.messageId())
                        .append("sequence", message.sequence()).append("text", message.text())
                        .append("truncated", message.truncated()))
                .toList();
        var replacement = new Document("_id", id)
                .append("owner_account_id", messages.isEmpty() ? "" : messages.getFirst().ownerAccountId())
                .append("generation", generation).append("deleted", false).append("messages", docs);
        var filter = new Document("_id", id).append("deleted", new Document("$ne", true))
                .append("generation", new Document("$lte", generation));
        try {
            mongo.getCollection(COLLECTION)
                    .replaceOne(filter, replacement, new ReplaceOptions().upsert(true));
        } catch (com.mongodb.MongoWriteException conflict) {
            if (conflict.getError().getCode() != 11000) {
                throw conflict;
            }
            // A newer generation or deletion tombstone won the race. Never overwrite it.
        }
    }

    @Override
    public List<Message> search(String owner, String query, int offset, int limit) {
        indexes();
        var expression = Pattern
                .compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        // Literal lexical matching, not caller-controlled regular expressions. The pipeline bounds output.
        var pipeline = List.of(
                new Document(
                        "$match",
                        new Document("owner_account_id", owner).append("deleted", false)
                                .append("messages.text", expression)),
                new Document("$unwind", "$messages"),
                new Document("$match", new Document("messages.text", expression)),
                new Document("$sort", new Document("_id", 1).append("messages.sequence", 1)),
                new Document("$skip", offset),
                new Document("$limit", limit));
        var result = new java.util.ArrayList<Message>();
        mongo.getCollection(COLLECTION).aggregate(pipeline).maxTime(2, TimeUnit.SECONDS).forEach(doc -> {
            Document message = doc.get("messages", Document.class);
            result.add(
                    new Message(
                            owner,
                            doc.getString("_id"),
                            message.getString("messageId"),
                            message.getInteger("sequence"),
                            doc.getLong("generation"),
                            message.getString("text"),
                            message.getBoolean("truncated", false)));
        });
        return List.copyOf(result);
    }

    @Override
    public void delete(String id) {
        mongo.getCollection(COLLECTION).replaceOne(
                new Document("_id", id),
                new Document("_id", id).append("deleted", true).append("generation", Long.MAX_VALUE),
                new ReplaceOptions().upsert(true));
    }
}
