package net.velocity.agent.monitoring;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.mongodb.WriteConcern;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;

import net.velocity.agent.persistence.ConflictException;
import net.velocity.agent.persistence.CorruptStateException;
import net.velocity.agent.persistence.NotFoundException;
import net.velocity.agent.state.TrustedScope;

/** Four bounded document families; no growing occurrence/observation arrays on job definitions. */
public final class MongoMonitoringRepository implements MonitoringRepository {

    public static final String JOBS = "agent_monitoring_jobs";
    public static final String RUNS = "agent_monitoring_runs";
    public static final String OBSERVATIONS = "agent_monitoring_observations";
    public static final String UPDATES = "agent_monitoring_updates";
    private static final int MAX_PAGE = 1000;
    private static final long MAX_QUERY_MILLIS = 2000;
    private static final List<String> UNRESOLVED = List.of("queued", "pending", "sending", "uncertain");
    private static final DateTimeFormatter ORDER = DateTimeFormatter
            .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'").withZone(ZoneOffset.UTC);
    private final MongoTemplate mongo;
    private final MonitoringRecordCodec codec = new MonitoringRecordCodec();
    private volatile boolean indexed;

    public MongoMonitoringRepository(MongoTemplate mongo) {
        this.mongo = Objects.requireNonNull(mongo, "Configured MongoTemplate");
    }

    @Override
    public MonitoringJob getJob(String id) {
        return get(JOBS, id, MonitoringJob.class);
    }

    @Override
    public void saveJob(MonitoringJob job) {
        save(JOBS, job, MonitoringJob.class);
    }

    @Override
    public List<MonitoringJob> listJobs(TrustedScope owner, int offset, int limit) {
        Objects.requireNonNull(owner, "Trusted owner");
        return page(
                JOBS,
                new Document("ownerAccountId", owner.requireOwnerAccountId()),
                new Document("_id", 1),
                offset,
                limit,
                MonitoringJob.class);
    }

    @Override
    public List<MonitoringJob> allJobs(int offset, int limit) {
        return page(JOBS, new Document(), new Document("_id", 1), offset, limit, MonitoringJob.class);
    }

    @Override
    public MonitoringRun getRun(String id) {
        return get(RUNS, id, MonitoringRun.class);
    }

    @Override
    public void saveRun(MonitoringRun run) {
        save(RUNS, run, MonitoringRun.class);
    }

    @Override
    public List<MonitoringRun> listRuns(String jobId, int offset, int limit) {
        return page(RUNS, job(jobId), recent(), offset, limit, MonitoringRun.class);
    }

    @Override
    public int activeRunCount() {
        ensureIndexes();
        Long count = mongo.execute(
                RUNS,
                raw -> raw.countDocuments(
                        new Document(
                                "state",
                                new Document("$in", List.of("prepared", "running", "finalizing"))),
                        new com.mongodb.client.model.CountOptions()
                                .maxTime(MAX_QUERY_MILLIS, TimeUnit.MILLISECONDS)));
        return (int) Math.min(Integer.MAX_VALUE, count == null ? 0 : count);
    }

    @Override
    public MonitoringObservation getObservation(String id) {
        return get(OBSERVATIONS, id, MonitoringObservation.class);
    }

    @Override
    public void saveObservation(MonitoringObservation observation) {
        MonitoringObservation detached = codec.copy(observation);
        ensureIndexes();
        Document existing = find(OBSERVATIONS, detached.id);
        if (existing != null) {
            sameObservation(existing, detached);
            return;
        }
        try {
            insert(OBSERVATIONS, document(detached));
        } catch (ConflictException conflict) {
            existing = find(OBSERVATIONS, detached.id);
            if (existing == null) {
                throw conflict;
            }
            sameObservation(existing, detached);
        }
    }

    @Override
    public List<MonitoringObservation> listObservations(String jobId, int offset, int limit) {
        return page(OBSERVATIONS, job(jobId), recent(), offset, limit, MonitoringObservation.class);
    }

    @Override
    public MonitoringObservation latestObservation(String jobId, String sourceId, String itemId) {
        var matches = page(
                OBSERVATIONS,
                job(jobId).append("_item", ObservationFingerprint.itemKey(sourceId, itemId)),
                recent(),
                0,
                1,
                MonitoringObservation.class);
        return matches.isEmpty() ? null : matches.getFirst();
    }

    @Override
    public MonitoringUpdate getUpdate(String id) {
        return get(UPDATES, id, MonitoringUpdate.class);
    }

    @Override
    public void saveUpdate(MonitoringUpdate update) {
        save(UPDATES, update, MonitoringUpdate.class);
    }

    @Override
    public List<MonitoringUpdate> listUpdates(String jobId, int offset, int limit) {
        return page(UPDATES, job(jobId), recent(), offset, limit, MonitoringUpdate.class);
    }

    @Override
    public List<MonitoringUpdate> pendingUpdates(String jobId) {
        return page(
                UPDATES,
                job(jobId).append("state", new Document("$in", UNRESOLVED)),
                new Document("_time", 1).append("_id", 1),
                0,
                MAX_PAGE,
                MonitoringUpdate.class);
    }

    @Override
    public List<MonitoringUpdate> unresolvedUpdates(String jobId, String afterId, int limit) {
        var filter = job(jobId).append("state", new Document("$in", UNRESOLVED));
        if (afterId != null) {
            filter.append("_id", new Document("$gt", MonitoringRecordCodec.id(afterId)));
        }
        return page(UPDATES, filter, new Document("_id", 1), 0, limit, MonitoringUpdate.class);
    }

    @Override
    public void prune(String jobId, Instant before, int retainLatestRuns) {
        job(jobId);
        Objects.requireNonNull(before, "Retention cutoff");
        if (retainLatestRuns < 0) {
            throw new IllegalArgumentException("Retained run count must be nonnegative");
        }
        ensureIndexes();
        // Finalization plans may refer to records not yet written. Never prune across that recovery gap.
        if (exists(
                RUNS,
                job(jobId).append("state", new Document("$nin", List.of("completed", "cancelled"))))) {
            return;
        }
        Document old = job(jobId).append("_time", new Document("$lt", ORDER.format(before)));
        var settled = new Document(old).append(
                "state",
                new Document(
                        "$in",
                        List.of("available", "delivered", "cancelled", "bundled")));
        scanOld(UPDATES, settled, MonitoringUpdate.class, update -> {
            if (!exists(UPDATES, job(jobId).append("sourceUpdateIds", update.id))) {
                delete(
                        UPDATES,
                        new Document("_id", update.id).append("storageVersion", update.storageVersion));
            }
        });
        Set<String> keep = new HashSet<>();
        for (int offset = 0; offset < retainLatestRuns;) {
            var retained = listRuns(jobId, offset, Math.min(MAX_PAGE, retainLatestRuns - offset));
            retained.forEach(run -> keep.add(run.id));
            offset += retained.size();
            if (retained.size() < MAX_PAGE) {
                break;
            }
        }
        scanOld(RUNS, old, MonitoringRun.class, run -> {
            if (!keep.contains(run.id) && !exists(UPDATES, job(jobId).append("runId", run.id))) {
                delete(RUNS, new Document("_id", run.id).append("storageVersion", run.storageVersion));
            }
        });
        scanOld(OBSERVATIONS, old, MonitoringObservation.class, observation -> {
            MonitoringObservation latest = latestObservation(jobId, observation.sourceId, observation.itemId);
            if (latest != null && !latest.id.equals(observation.id)
                    && !exists(UPDATES, job(jobId).append("observationIds", observation.id))
                    && !exists(RUNS, job(jobId).append("observationIds", observation.id))) {
                delete(OBSERVATIONS, new Document("_id", observation.id));
            }
        });
    }

    /** Keyset pages remain stable when records are deleted and pinned records fill a complete page. */
    private <T> void scanOld(String collection, Document filter, Class<T> type, Consumer<T> action) {
        Document cursor = new Document(filter);
        while (true) {
            List<T> values = page(collection, cursor, recent(), 0, MAX_PAGE, type);
            if (values.isEmpty()) {
                return;
            }
            values.forEach(action);
            if (values.size() < MAX_PAGE) {
                return;
            }
            T last = values.getLast();
            String lastTime = ORDER.format(time(last));
            cursor = new Document(filter).append(
                    "$or",
                    List.of(
                            new Document("_time", new Document("$lt", lastTime)),
                            new Document("_time", lastTime).append("_id", new Document("$lt", id(last)))));
        }
    }

    private <T> T get(String collection, String id, Class<T> type) {
        ensureIndexes();
        Document saved = find(collection, MonitoringRecordCodec.id(id));
        if (saved == null) {
            throw new NotFoundException("Monitoring record not found: " + id);
        }
        return record(saved, type);
    }

    private <T> void save(String collection, T value, Class<T> type) {
        T snapshot = codec.copy(value, type);
        long expected = storageVersion(snapshot);
        if (expected == Long.MAX_VALUE) {
            throw new ConflictException("Monitoring storage version is exhausted");
        }
        ensureIndexes();
        if (expected == 0) {
            storageVersion(snapshot, 1);
            insert(collection, document(snapshot));
        } else {
            Document before = find(collection, id(snapshot));
            if (before == null || storageVersion(record(before, type)) != expected) {
                throw new ConflictException("Monitoring record was concurrently modified");
            }
            MonitoringRecordCodec.validateReplacement(record(before, type), snapshot);
            storageVersion(snapshot, expected + 1);
            Document replacement = document(snapshot);
            boolean changed = Boolean.TRUE.equals(mongo.execute(collection, raw -> {
                var result = durable(raw).replaceOne(
                        new Document("_id", id(snapshot))
                                .append("storageVersion", expected),
                        replacement);
                if (!result.wasAcknowledged()) {
                    throw new IllegalStateException("Monitoring writes require acknowledgement");
                }
                return result.getMatchedCount() == 1;
            }));
            if (!changed) {
                throw new ConflictException("Monitoring record was concurrently modified");
            }
        }
        storageVersion(value, expected + 1);
    }

    private <T> List<T> page(
            String collection,
            Document filter,
            Document order,
            int offset,
            int limit,
            Class<T> type) {
        if (offset < 0 || limit < 1 || limit > MAX_PAGE) {
            throw new IllegalArgumentException(
                    "Monitoring pagination requires a nonnegative offset and limit 1..1000");
        }
        ensureIndexes();
        List<T> results = mongo.execute(
                collection,
                raw -> raw.find(filter).sort(order).skip(offset).limit(limit)
                        .maxTime(MAX_QUERY_MILLIS, TimeUnit.MILLISECONDS)
                        .map(document -> record(document, type))
                        .into(new ArrayList<>()));
        return List.copyOf(results);
    }

    private Document find(String collection, String id) {
        return mongo.execute(
                collection,
                raw -> raw.find(new Document("_id", id))
                        .maxTime(MAX_QUERY_MILLIS, TimeUnit.MILLISECONDS).first());
    }

    private boolean exists(String collection, Document filter) {
        return Boolean.TRUE.equals(
                mongo.execute(
                        collection,
                        raw -> raw.find(filter).projection(new Document("_id", 1))
                                .limit(1).maxTime(MAX_QUERY_MILLIS, TimeUnit.MILLISECONDS).first() != null));
    }

    private void insert(String collection, Document document) {
        try {
            mongo.execute(collection, raw -> {
                if (!durable(raw).insertOne(document).wasAcknowledged()) {
                    throw new IllegalStateException("Monitoring writes require acknowledgement");
                }
                return null;
            });
        } catch (DuplicateKeyException failure) {
            throw new ConflictException("Monitoring identity already exists");
        }
    }

    private void delete(String collection, Document filter) {
        mongo.execute(collection, raw -> {
            if (!durable(raw).deleteOne(filter).wasAcknowledged()) {
                throw new IllegalStateException("Monitoring writes require acknowledgement");
            }
            return null;
        });
    }

    private static MongoCollection<Document> durable(MongoCollection<Document> raw) {
        if (!raw.getWriteConcern().isAcknowledged()) {
            throw new IllegalStateException("Monitoring writes require acknowledged MongoDB writes");
        }
        return raw.withWriteConcern(WriteConcern.MAJORITY.withJournal(true));
    }

    private Document document(Object value) {
        Document document = Document.parse(new String(codec.encode(value), StandardCharsets.UTF_8));
        document.append("_id", id(value)).append("_time", ORDER.format(time(value)));
        if (value instanceof MonitoringObservation observation) {
            document.append(
                    "_item",
                    ObservationFingerprint.itemKey(observation.sourceId, observation.itemId));
        }
        return document;
    }

    private <T> T record(Document document, Class<T> type) {
        Document snapshot = new Document(document);
        Object persistedId = snapshot.remove("_id");
        snapshot.remove("_time");
        snapshot.remove("_item");
        T value = codec.decode(snapshot.toJson().getBytes(StandardCharsets.UTF_8), type);
        if (!Objects.equals(persistedId, id(value))) {
            throw new CorruptStateException("Monitoring record identity mismatch");
        }
        if (!(value instanceof MonitoringObservation) && storageVersion(value) < 1) {
            throw new CorruptStateException("Invalid monitoring storage version");
        }
        return value;
    }

    private void sameObservation(Document saved, MonitoringObservation observation) {
        MonitoringObservation existing = record(saved, MonitoringObservation.class);
        if (!codec.mapper.valueToTree(existing).equals(codec.mapper.valueToTree(observation))) {
            throw new ConflictException("Monitoring observations are immutable");
        }
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
                    JOBS,
                    raw -> raw.createIndexes(
                            List.of(
                                    index(
                                            "monitor_owner",
                                            new Document("ownerAccountId", 1).append("_id", 1)))));
            mongo.execute(
                    RUNS,
                    raw -> raw.createIndexes(
                            List.of(
                                    index(
                                            "monitor_runs",
                                            new Document("jobId", 1).append("_time", -1).append("_id", -1)),
                                    index("monitor_run_state", new Document("jobId", 1).append("state", 1)),
                                    index("monitor_active_runs", new Document("state", 1)),
                                    new IndexModel(
                                            new Document("scheduleId", 1).append("occurrenceId", 1),
                                            new IndexOptions().name("monitor_occurrence").unique(true)),
                                    new IndexModel(
                                            new Document("executionId", 1),
                                            new IndexOptions().name("monitor_execution").unique(true)))));
            mongo.execute(
                    OBSERVATIONS,
                    raw -> raw.createIndexes(
                            List.of(
                                    index(
                                            "monitor_observations",
                                            new Document("jobId", 1).append("_time", -1).append("_id", -1)),
                                    index(
                                            "monitor_item_versions",
                                            new Document("jobId", 1).append("_item", 1)
                                                    .append("_time", -1).append("_id", -1)))));
            mongo.execute(
                    UPDATES,
                    raw -> raw.createIndexes(
                            List.of(
                                    index(
                                            "monitor_updates",
                                            new Document("jobId", 1).append("_time", -1).append("_id", -1)),
                                    index(
                                            "monitor_pending_updates",
                                            new Document("jobId", 1).append("state", 1).append("_time", 1)),
                                    index(
                                            "monitor_unresolved_pages",
                                            new Document("jobId", 1).append("state", 1).append("_id", 1)),
                                    index(
                                            "monitor_update_observations",
                                            new Document("jobId", 1).append("observationIds", 1)),
                                    index(
                                            "monitor_update_sources",
                                            new Document("jobId", 1).append("sourceUpdateIds", 1)))));
            indexed = true;
        }
    }

    private static IndexModel index(String name, Document keys) {
        return new IndexModel(keys, new IndexOptions().name(name));
    }

    private static Document job(String id) {
        return new Document("jobId", MonitoringRecordCodec.id(id));
    }

    private static Document recent() {
        return new Document("_time", -1).append("_id", -1);
    }

    private static String id(Object value) {
        return switch (value) {
            case MonitoringJob job -> job.id;
            case MonitoringRun run -> run.id;
            case MonitoringObservation observation -> observation.id;
            case MonitoringUpdate update -> update.id;
            default -> throw new IllegalArgumentException("Unknown monitoring record");
        };
    }

    private static Instant time(Object value) {
        return switch (value) {
            case MonitoringJob job -> job.createdAt;
            case MonitoringRun run -> run.createdAt;
            case MonitoringObservation observation -> observation.observedAt;
            case MonitoringUpdate update -> update.createdAt;
            default -> throw new IllegalArgumentException("Unknown monitoring record");
        };
    }

    private static long storageVersion(Object value) {
        return switch (value) {
            case MonitoringJob job -> job.storageVersion;
            case MonitoringRun run -> run.storageVersion;
            case MonitoringUpdate update -> update.storageVersion;
            default -> throw new IllegalArgumentException("Unknown mutable monitoring record");
        };
    }

    private static void storageVersion(Object value, long version) {
        switch (value) {
            case MonitoringJob job -> job.storageVersion = version;
            case MonitoringRun run -> run.storageVersion = version;
            case MonitoringUpdate update -> update.storageVersion = version;
            default -> throw new IllegalArgumentException("Unknown mutable monitoring record");
        }
    }
}
