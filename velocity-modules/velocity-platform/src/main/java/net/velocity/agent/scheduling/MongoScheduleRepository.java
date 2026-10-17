package net.velocity.agent.scheduling;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.mongodb.WriteConcern;
import com.mongodb.client.MongoCollection;

import net.velocity.agent.persistence.ConflictException;
import net.velocity.agent.persistence.CorruptStateException;
import net.velocity.agent.persistence.NotFoundException;

import static net.velocity.agent.scheduling.ScheduleRecordCodec.name;

/** Authoritative schedule definitions and occurrence outcomes, atomically committed in MongoDB. */
public final class MongoScheduleRepository implements ScheduleRepository {

    public static final String COLLECTION = "agent_schedules";
    private static final long MAX_QUERY_MILLIS = 2_000;
    private final MongoTemplate mongo;
    private final ScheduleRecordCodec codec = new ScheduleRecordCodec();

    public MongoScheduleRepository(MongoTemplate mongo) {
        this.mongo = Objects.requireNonNull(mongo, "Configured MongoTemplate");
    }

    @Override
    public List<ScheduleRecord> list() {
        List<ScheduleRecord> records = mongo.execute(
                COLLECTION,
                collection -> collection.find()
                        .sort(new Document("_id", 1)).maxTime(MAX_QUERY_MILLIS, TimeUnit.MILLISECONDS)
                        .map(this::record).into(new ArrayList<>()));
        return List.copyOf(records);
    }

    @Override
    public ScheduleRecord get(String id) {
        Document saved = find(name(id));
        if (saved == null) {
            throw new NotFoundException("Schedule not found: " + id);
        }
        return record(saved);
    }

    @Override
    public synchronized void save(ScheduleRecord schedule) {
        ScheduleRecord saved = codec.copy(schedule);
        Document replacement = document(saved);
        Document before = find(saved.id);
        if (before == null) {
            insert(replacement);
            return;
        }
        ScheduleRecordCodec.validateReplacement(record(before), saved);
        replacement.put("storageVersion", nextVersion(before));
        replace(before, replacement);
    }

    private Document find(String id) {
        return mongo.execute(
                COLLECTION,
                collection -> collection.find(new Document("_id", id))
                        .maxTime(MAX_QUERY_MILLIS, TimeUnit.MILLISECONDS).first());
    }

    private void insert(Document document) {
        try {
            mongo.execute(COLLECTION, collection -> {
                acknowledged(collection);
                var durable = collection.withWriteConcern(WriteConcern.MAJORITY.withJournal(true));
                if (!durable.insertOne(document).wasAcknowledged()) {
                    throw new IllegalStateException("Schedule writes require acknowledged MongoDB writes");
                }
                return null;
            });
        } catch (DuplicateKeyException failure) {
            throw new ConflictException("Schedule was concurrently created: " + document.getString("_id"));
        }
    }

    private void replace(Document before, Document replacement) {
        boolean changed = Boolean.TRUE.equals(mongo.execute(COLLECTION, collection -> {
            acknowledged(collection);
            var durable = collection.withWriteConcern(WriteConcern.MAJORITY.withJournal(true));
            var result = durable.replaceOne(
                    new Document("_id", before.getString("_id"))
                            .append("storageVersion", version(before)),
                    replacement);
            if (!result.wasAcknowledged()) {
                throw new IllegalStateException("Schedule writes require acknowledged MongoDB writes");
            }
            return result.getMatchedCount() == 1;
        }));
        if (!changed) {
            throw new ConflictException("Schedule was concurrently modified: " + before.getString("_id"));
        }
    }

    private static void acknowledged(MongoCollection<Document> collection) {
        if (!collection.getWriteConcern().isAcknowledged()) {
            throw new IllegalStateException("Schedule writes require acknowledged MongoDB writes");
        }
    }

    private Document document(ScheduleRecord record) {
        return Document.parse(new String(codec.encode(record), StandardCharsets.UTF_8))
                .append("_id", record.id).append("storageVersion", 1L);
    }

    private ScheduleRecord record(Document document) {
        try {
            String id = name(document.getString("_id"));
            version(document);
            Document snapshot = new Document(document);
            snapshot.remove("_id");
            snapshot.remove("storageVersion");
            return codec.decode(snapshot.toJson().getBytes(StandardCharsets.UTF_8), id);
        } catch (IllegalArgumentException | ClassCastException failure) {
            throw new CorruptStateException("Corrupt schedule database state", failure);
        }
    }

    private static long version(Document document) {
        Object value = document.get("storageVersion");
        if (!(value instanceof Long || value instanceof Integer) || ((Number) value).longValue() < 1) {
            throw new CorruptStateException("Invalid schedule storage version");
        }
        return ((Number) value).longValue();
    }

    private static long nextVersion(Document document) {
        try {
            return Math.incrementExact(version(document));
        } catch (ArithmeticException failure) {
            throw new ConflictException("Schedule storage version is exhausted");
        }
    }
}
