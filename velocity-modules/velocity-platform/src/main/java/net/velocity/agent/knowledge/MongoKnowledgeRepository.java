package net.velocity.agent.knowledge;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.bson.Document;
import org.bson.types.Decimal128;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;

import net.velocity.util.BsonJson;

/** MongoDB is authoritative; discovery sidecars are generated from committed records. */
public final class MongoKnowledgeRepository implements KnowledgeRepository {

    public static final String COLLECTION = "agent_knowledge_records";
    private static final int MAX_PAGE = 101;
    private static final long MAX_QUERY_MILLIS = 2_000;
    private static final Set<String> FILTER_FIELDS = Set.of(
            "dataset",
            "key",
            "title",
            "description",
            "tags",
            "created_at",
            "updated_at",
            "revision");
    private static final Set<String> SORT_FIELDS = Set.of(
            "dataset",
            "key",
            "title",
            "created_at",
            "updated_at",
            "revision");
    private final MongoTemplate mongo;
    private volatile boolean indexed;

    public MongoKnowledgeRepository(MongoTemplate mongo) {
        this.mongo = Objects.requireNonNull(mongo, "Configured MongoTemplate");
    }

    @Override
    public void insert(KnowledgeRecord record) {
        validateRecord(record);
        Document replacement = document(record);
        ensureIndexes();
        mongo.execute(COLLECTION, collection -> {
            if (!collection.getWriteConcern().isAcknowledged()) {
                throw new IllegalStateException("Knowledge writes require acknowledged MongoDB writes");
            }
            if (!collection.insertOne(replacement).wasAcknowledged()) {
                throw new IllegalStateException("Knowledge writes require acknowledged MongoDB writes");
            }
            return null;
        });
    }

    @Override
    public boolean replace(KnowledgeRecord record, long expectedRevision) {
        validateRecord(record);
        if (expectedRevision < 1 || expectedRevision == Long.MAX_VALUE
                || record.revision() != expectedRevision + 1) {
            throw new IllegalArgumentException("Replacement must increment the expected revision once");
        }
        Document replacement = document(record);
        ensureIndexes();
        // Identity cannot be moved to a different owner, dataset or key by a replacement.
        Document filter = owner(record.ownerAccountId()).append("_id", record.id())
                .append("dataset", record.dataset()).append("key", record.key())
                .append("revision", expectedRevision);
        return Boolean.TRUE.equals(mongo.execute(COLLECTION, collection -> {
            if (!collection.getWriteConcern().isAcknowledged()) {
                throw new IllegalStateException("Knowledge writes require acknowledged MongoDB writes");
            }
            var result = collection.replaceOne(filter, replacement);
            if (!result.wasAcknowledged()) {
                throw new IllegalStateException("Knowledge writes require acknowledged MongoDB writes");
            }
            return result.getModifiedCount() == 1;
        }));
    }

    @Override
    public boolean delete(String ownerAccountId, String id, long expectedRevision) {
        require(id, "Record ID");
        if (expectedRevision < 1) {
            throw new IllegalArgumentException("Deletion requires the current revision");
        }
        ensureIndexes();
        Document filter = owner(ownerAccountId).append("_id", id).append("revision", expectedRevision);
        return Boolean.TRUE.equals(mongo.execute(COLLECTION, collection -> {
            if (!collection.getWriteConcern().isAcknowledged()) {
                throw new IllegalStateException("Knowledge writes require acknowledged MongoDB writes");
            }
            var result = collection.deleteOne(filter);
            if (!result.wasAcknowledged()) {
                throw new IllegalStateException("Knowledge writes require acknowledged MongoDB writes");
            }
            return result.getDeletedCount() == 1;
        }));
    }

    @Override
    public long count(String ownerAccountId, String dataset) {
        require(dataset, "Dataset");
        ensureIndexes();
        Document filter = owner(ownerAccountId).append("dataset", dataset);
        Long count = mongo.execute(
                COLLECTION,
                collection -> collection.countDocuments(
                        filter,
                        new com.mongodb.client.model.CountOptions()
                                .maxTime(MAX_QUERY_MILLIS, TimeUnit.MILLISECONDS)));
        return count == null ? 0 : count;
    }

    @Override
    public Optional<KnowledgeRecord> findById(String ownerAccountId, String id) {
        require(id, "Record ID");
        return one(owner(ownerAccountId).append("_id", id));
    }

    @Override
    public Optional<KnowledgeRecord> findByKey(String ownerAccountId, String dataset, String key) {
        require(dataset, "Dataset");
        require(key, "Key");
        return one(owner(ownerAccountId).append("dataset", dataset).append("key", key));
    }

    private Optional<KnowledgeRecord> one(Document filter) {
        ensureIndexes();
        return Optional.ofNullable(
                mongo.execute(
                        COLLECTION,
                        collection -> collection.find(filter)
                                .maxTime(MAX_QUERY_MILLIS, TimeUnit.MILLISECONDS).first()))
                .map(this::record);
    }

    @Override
    public List<KnowledgeRecord> query(String ownerAccountId, KnowledgeQuery query) {
        Objects.requireNonNull(query, "Query");
        page(query.limit());
        if (query.offset() < 0 || query.offset() > 1_000_000) {
            throw new IllegalArgumentException("Offset must be between 0 and 1000000");
        }
        if (query.filters().size() > 20) {
            throw new IllegalArgumentException("At most 20 filters are supported");
        }
        String sort = query.sortField() == null ? "updated_at" : query.sortField();
        field(sort, true);
        List<Document> clauses = new ArrayList<>();
        clauses.add(owner(ownerAccountId));
        if (query.dataset() != null) {
            require(query.dataset(), "Dataset");
            clauses.add(new Document("dataset", query.dataset()));
        }
        for (KnowledgeQuery.Filter filter : query.filters()) {
            clauses.add(filter(filter));
        }
        if (query.search() != null && !query.search().isBlank()) {
            Pattern literal = literal(query.search());
            clauses.add(
                    new Document(
                            "$or",
                            List.of(
                                    new Document("title", literal),
                                    new Document("description", literal),
                                    new Document("tags", literal),
                                    new Document("dataset", literal),
                                    new Document("key", literal))));
        }
        Document condition = new Document("$and", clauses);
        Document order = new Document(sort, query.descending() ? -1 : 1).append("_id", 1);
        ensureIndexes();
        return mongo.execute(
                COLLECTION,
                collection -> collection.find(condition).sort(order)
                        .skip(query.offset()).limit(query.limit())
                        .maxTime(MAX_QUERY_MILLIS, TimeUnit.MILLISECONDS)
                        .map(this::record).into(new ArrayList<>()));
    }

    @Override
    public KnowledgeAggregation.Result aggregate(String ownerAccountId, KnowledgeAggregation aggregation) {
        Objects.requireNonNull(aggregation, "Aggregation");
        require(aggregation.dataset(), "Dataset");
        if (!KnowledgeAggregation.OPERATIONS.contains(aggregation.operation())) {
            throw new IllegalArgumentException("Unsupported aggregation operation");
        }
        boolean counting = aggregation.operation().equals("count");
        if (counting != (aggregation.field() == null)) {
            throw new IllegalArgumentException("count takes no field; every other operation requires one");
        }
        if (!counting) {
            field(aggregation.field(), false);
            if (!aggregation.field().startsWith("content.")) {
                throw new IllegalArgumentException("Aggregation field must be a content path");
            }
        }
        if (aggregation.groupBy() != null) {
            field(aggregation.groupBy(), false);
        }
        if (aggregation.groupLimit() < 1 || aggregation.groupLimit() > KnowledgeAggregation.MAX_GROUPS) {
            throw new IllegalArgumentException("Group limit must be 1 to 100");
        }
        if (aggregation.filters().size() > 20) {
            throw new IllegalArgumentException("At most 20 filters are supported");
        }
        List<Document> clauses = new ArrayList<>();
        clauses.add(owner(ownerAccountId));
        clauses.add(new Document("dataset", aggregation.dataset()));
        for (KnowledgeQuery.Filter filter : aggregation.filters()) {
            clauses.add(filter(filter));
        }
        Document group = new Document(
                "_id",
                aggregation.groupBy() == null ? null : "$" + aggregation.groupBy())
                .append("count", new Document("$sum", 1));
        if (counting) {
            group.append("skipped", new Document("$sum", 0));
        } else {
            // Only numbers feed the accumulator; a missing or non-numeric field is skipped and counted.
            String path = "$" + aggregation.field();
            Document numeric = new Document("$isNumber", path);
            group.append(
                    "value",
                    new Document(
                            "$" + aggregation.operation(),
                            new Document("$cond", Arrays.asList(numeric, path, null))))
                    .append("skipped", new Document("$sum", new Document("$cond", List.of(numeric, 0, 1))));
        }
        Document order = new Document(counting ? "count" : "value", -1).append("_id", 1);
        Document totals = new Document("_id", null).append("groups", new Document("$sum", 1))
                .append("count", new Document("$sum", "$count"))
                .append("skipped", new Document("$sum", "$skipped"));
        List<Document> pipeline = List.of(
                new Document("$match", new Document("$and", clauses)),
                new Document("$group", group),
                new Document(
                        "$facet",
                        new Document(
                                "groups",
                                List.of(
                                        new Document("$sort", order),
                                        new Document("$limit", aggregation.groupLimit() + 1)))
                                .append("totals", List.of(new Document("$group", totals)))));
        ensureIndexes();
        Document facets = mongo.execute(
                COLLECTION,
                collection -> collection.aggregate(pipeline).allowDiskUse(false)
                        .maxTime(MAX_QUERY_MILLIS, TimeUnit.MILLISECONDS).first());
        if (facets == null) {
            return new KnowledgeAggregation.Result(List.of(), 0, 0, false);
        }
        List<Document> groups = facets.getList("groups", Document.class);
        List<Document> summary = facets.getList("totals", Document.class);
        boolean limitReached = groups.size() > aggregation.groupLimit();
        var result = new ArrayList<KnowledgeAggregation.Group>();
        for (Document entry : groups.subList(0, Math.min(groups.size(), aggregation.groupLimit()))) {
            long count = ((Number) entry.get("count")).longValue();
            result.add(
                    new KnowledgeAggregation.Group(
                            key(entry.get("_id")),
                            count,
                            counting ? count : number(entry.get("value")),
                            ((Number) entry.get("skipped")).longValue()));
        }
        long count = 0, skipped = 0;
        if (!summary.isEmpty()) {
            count = ((Number) summary.getFirst().get("count")).longValue();
            skipped = ((Number) summary.getFirst().get("skipped")).longValue();
        }
        return new KnowledgeAggregation.Result(result, count, skipped, limitReached);
    }

    private static Object key(Object value) {
        if (value instanceof Date date) {
            return date.toInstant().toString();
        }
        return json(value);
    }

    private static Object number(Object value) {
        if (value instanceof Decimal128 decimal && (decimal.isNaN() || decimal.isInfinite())) {
            throw new IllegalStateException("Aggregate is outside the Decimal128 range");
        }
        return json(value);
    }

    @Override
    public List<String> datasets(String ownerAccountId, String after, int limit, String search) {
        page(limit);
        List<Document> clauses = new ArrayList<>();
        clauses.add(owner(ownerAccountId));
        if (after != null) {
            clauses.add(new Document("dataset", new Document("$gt", after)));
        }
        if (search != null && !search.isBlank()) {
            clauses.add(new Document("dataset", literal(search)));
        }
        List<Document> pipeline = List.of(
                new Document("$match", new Document("$and", clauses)),
                new Document("$group", new Document("_id", "$dataset")),
                new Document("$sort", new Document("_id", 1)),
                new Document("$limit", limit));
        ensureIndexes();
        return mongo.execute(
                COLLECTION,
                collection -> collection.aggregate(pipeline).allowDiskUse(false)
                        .maxTime(MAX_QUERY_MILLIS, TimeUnit.MILLISECONDS)
                        .map(item -> item.getString("_id")).into(new ArrayList<>()));
    }

    @Override
    public Stream<KnowledgeRecord> scan(String ownerAccountId) {
        owner(ownerAccountId);
        ensureIndexes();
        Query query = new Query(Criteria.where("owner_account_id").is(ownerAccountId))
                .with(Sort.by("dataset", "key")).cursorBatchSize(100)
                .maxTime(Duration.ofMillis(MAX_QUERY_MILLIS));
        return mongo.stream(query, Document.class, COLLECTION).map(this::record);
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
                                            new Document("owner_account_id", 1).append("dataset", 1)
                                                    .append("key", 1),
                                            new IndexOptions().name("knowledge_identity").unique(true)),
                                    index(
                                            "knowledge_owner_account_id",
                                            new Document("owner_account_id", 1).append("_id", 1)),
                                    index(
                                            "knowledge_dataset_updated",
                                            new Document("owner_account_id", 1).append("dataset", 1)
                                                    .append("updated_at", -1).append("_id", 1)),
                                    index(
                                            "knowledge_owner_updated",
                                            new Document("owner_account_id", 1)
                                                    .append("updated_at", -1).append("_id", 1)),
                                    index(
                                            "knowledge_dataset_tags",
                                            new Document("owner_account_id", 1).append("dataset", 1)
                                                    .append("tags", 1).append("updated_at", -1)
                                                    .append("_id", 1)))));
            indexed = true;
        }
    }

    private static IndexModel index(String name, Document keys) {
        return new IndexModel(keys, new IndexOptions().name(name));
    }

    private static Document filter(KnowledgeQuery.Filter filter) {
        Objects.requireNonNull(filter, "Filter");
        field(filter.field(), false);
        Object value = filter.value();
        String operator = Objects.requireNonNull(filter.operator(), "Filter operator");
        switch (operator) {
            case "eq" -> value = scalar(filter.field(), value);
            case "gt", "gte", "lt", "lte" -> {
                if (!(value instanceof String || value instanceof Number)) {
                    throw new IllegalArgumentException("Comparison values must be strings or numbers");
                }
                value = scalar(filter.field(), value);
            }
            case "in" -> {
                if (!(value instanceof List<?> values) || values.isEmpty() || values.size() > 20) {
                    throw new IllegalArgumentException("in requires 1 to 20 scalar values");
                }
                value = values.stream().map(item -> scalar(filter.field(), item)).toList();
            }
            case "exists" -> {
                if (!(value instanceof Boolean)) {
                    throw new IllegalArgumentException("exists requires a boolean");
                }
            }
            case "contains" -> {
                if (!(value instanceof String text)) {
                    throw new IllegalArgumentException("contains requires a string");
                }
                if ("tags".equals(filter.field())) {
                    operator = "eq";
                } else if (filter.field().startsWith("content.")) {
                    // A case-insensitive literal substring over a string field, or any string in an array.
                    return new Document(filter.field(), literal(text));
                } else {
                    throw new IllegalArgumentException("contains is supported for tags and content fields");
                }
            }
            default -> throw new IllegalArgumentException("Unsupported filter operator");
        }
        return new Document(filter.field(), new Document("$" + operator, value));
    }

    private static Object scalar(String field, Object value) {
        if (value != null
                && !(value instanceof String || value instanceof Boolean || value instanceof Number)) {
            throw new IllegalArgumentException("Filter values must be JSON scalars");
        }
        if (value instanceof Double doubleValue && !Double.isFinite(doubleValue)
                || value instanceof Float floatValue && !Float.isFinite(floatValue)) {
            throw new IllegalArgumentException("Filter numbers must be finite");
        }
        if ((field.equals("created_at") || field.equals("updated_at")) && value != null) {
            if (!(value instanceof String timestamp)) {
                throw new IllegalArgumentException("Timestamp filters require ISO-8601 strings");
            }
            try {
                return Date.from(Instant.parse(timestamp));
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("Invalid ISO-8601 timestamp", e);
            }
        }
        return bson(value);
    }

    private static void field(String field, boolean sort) {
        if (field == null || field.length() > 128 || !(sort ? SORT_FIELDS : FILTER_FIELDS).contains(field)
                && (sort || !field.matches("content\\.[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*"))) {
            throw new IllegalArgumentException("Unsupported query field");
        }
        if (field.split("\\.").length > 17) {
            throw new IllegalArgumentException("Query field is too deeply nested");
        }
    }

    private static Pattern literal(String search) {
        if (search.length() > 512) {
            throw new IllegalArgumentException("Search is limited to 512 characters");
        }
        return Pattern.compile(Pattern.quote(search), Pattern.CASE_INSENSITIVE);
    }

    private static Document owner(String ownerAccountId) {
        require(ownerAccountId, "Owner");
        return new Document("owner_account_id", ownerAccountId);
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    private static void page(int limit) {
        if (limit < 1 || limit > MAX_PAGE) {
            throw new IllegalArgumentException("Page limit must be 1 to 101");
        }
    }

    private static void validateRecord(KnowledgeRecord record) {
        Objects.requireNonNull(record, "Record");
        require(record.id(), "Record ID");
        require(record.ownerAccountId(), "Owner");
        require(record.dataset(), "Dataset");
        require(record.key(), "Key");
        require(record.createdByExecutionId(), "Creating execution");
        require(record.updatedByExecutionId(), "Updating execution");
        if (record.revision() < 1 || record.createdAt() == null || record.updatedAt() == null
                || record.updatedAt().isBefore(record.createdAt())) {
            throw new IllegalArgumentException("Invalid revision or timestamps");
        }
    }

    private static Document document(KnowledgeRecord record) {
        return new Document("_id", record.id()).append("owner_account_id", record.ownerAccountId())
                .append("dataset", record.dataset()).append("key", record.key())
                .append("title", record.title()).append("description", record.description())
                .append("tags", record.tags()).append("content", bson(record.content()))
                .append("revision", record.revision()).append("created_at", Date.from(record.createdAt()))
                .append("updated_at", Date.from(record.updatedAt()))
                .append("created_by_execution_id", record.createdByExecutionId())
                .append("updated_by_execution_id", record.updatedByExecutionId())
                .append("observed_fields", record.observedFields());
    }

    @SuppressWarnings("unchecked")
    private KnowledgeRecord record(Document document) {
        return new KnowledgeRecord(
                document.getString("_id"),
                document.getString("owner_account_id"),
                document.getString("dataset"),
                document.getString("key"),
                document.getString("title"),
                document.getString("description"),
                document.getList("tags", String.class),
                (Map<String, Object>) json(document.get("content")),
                ((Number) document.get("revision")).longValue(),
                document.getDate("created_at").toInstant(),
                document.getDate("updated_at").toInstant(),
                document.getString("created_by_execution_id"),
                document.getString("updated_by_execution_id"),
                document.getList("observed_fields", String.class));
    }

    private static Object bson(Object value) {
        if (value instanceof Map<?, ?> map) {
            Document converted = new Document();
            map.forEach((key, child) -> converted.put((String) key, bson(child)));
            return converted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(MongoKnowledgeRepository::bson).toList();
        }
        if (value instanceof BigDecimal || value instanceof BigInteger) {
            try {
                return new Decimal128(
                        value instanceof BigDecimal decimal ? decimal : new BigDecimal((BigInteger) value));
            } catch (ArithmeticException | NumberFormatException error) {
                throw new IllegalArgumentException(
                        "JSON number exceeds MongoDB Decimal128 precision or range",
                        error);
            }
        }
        if (value instanceof Double doubleValue && !Double.isFinite(doubleValue)
                || value instanceof Float floatValue && !Float.isFinite(floatValue)) {
            throw new IllegalArgumentException("JSON numbers must be finite");
        }
        return value;
    }

    private static Object json(Object value) {
        return BsonJson.decode(value);
    }
}
