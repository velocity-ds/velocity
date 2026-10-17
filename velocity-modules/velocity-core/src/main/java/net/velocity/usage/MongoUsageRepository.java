package net.velocity.usage;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.bson.Document;
import org.bson.types.Decimal128;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.mongodb.MongoWriteException;

import net.velocity.inference.objects.InferenceResponse.Usage;

/** Financial metadata is retained independently of execution deletion, without a TTL. */
public final class MongoUsageRepository implements UsageRepository {

    public static final String COLLECTION = "inference_usage";
    private final MongoTemplate mongo;
    private final UsageActivities activities;
    private volatile boolean indexed;

    public MongoUsageRepository(MongoTemplate mongo, UsageActivities activities) {
        this.mongo = mongo;
        this.activities = Objects.requireNonNull(activities);
    }

    @Override
    public void insert(UsageRecord record) {
        indexes();
        acknowledged();
        try {
            mongo.getCollection(COLLECTION).insertOne(encode(record));
        } catch (MongoWriteException conflict) {
            if (conflict.getError().getCode() != 11000) {
                throw conflict;
            }
            var old = find(record.requestId()).orElseThrow(() -> conflict);
            if (!old.context().equals(record.context()) || !old.provider().equals(record.provider())
                    || !old.requestedModel().equals(record.requestedModel())) {
                throw new IllegalStateException("Usage intent identity collision");
            }
            // Persistence retry retains the original timestamp and pricing, including after restart.
        }
    }

    @Override
    public Optional<UsageRecord> find(String id) {
        return Optional.ofNullable(mongo.getCollection(COLLECTION).find(new Document("_id", id)).first())
                .map(this::decode);
    }

    @Override
    public boolean replace(UsageRecord record, long expectedRevision) {
        acknowledged();
        if (record.revision() != expectedRevision + 1) {
            throw new IllegalArgumentException("Usage revision must increment");
        }
        var result = mongo.getCollection(COLLECTION).replaceOne(
                new Document("_id", record.requestId())
                        .append("revision", expectedRevision),
                encode(record));
        return result.wasAcknowledged() && result.getModifiedCount() == 1;
    }

    @Override
    public List<UsageReports.Breakdown> aggregate(Filter filter) {
        indexes();
        if (filter.ownerAccountId() == null || filter.ownerAccountId().isBlank()) {
            throw new IllegalArgumentException("Account reports require a trusted owner");
        }
        var match = new Document("ownerAccountId", filter.ownerAccountId());
        if (filter.from() != null) {
            match.put(
                    "startedAt",
                    new Document("$gte", Date.from(filter.from()))
                            .append("$lt", Date.from(Objects.requireNonNull(filter.toExclusive()))));
        }
        if (filter.rootExecutionId() != null) {
            match.put("rootExecutionId", filter.rootExecutionId());
            match.put("activity", new Document("$ne", "learning"));
        }
        if (filter.provider() != null) {
            match.put("provider", filter.provider());
        }
        if (filter.model() != null) {
            match.put("model", filter.model());
        }
        if (filter.activity() != null) {
            match.put("activity", filter.activity());
        }
        var group = new Document(
                "_id",
                new Document("provider", "$provider").append("model", "$model")
                        .append("activity", "$activity").append("rates", "$rates"))
                .append("requests", new Document("$sum", 1))
                .append("estimatedUsd", new Document("$sum", "$estimatedUsd"))
                .append("pricedRequests", conditionalSum("$priced"))
                .append("unpricedRequests", conditionalSum("$unpriced"))
                .append("incompleteRequests", conditionalSum("$incomplete"))
                .append("partialRequests", conditionalSum("$partial"))
                .append("unsupportedCategories", new Document("$addToSet", "$unsupportedCategories"));
        for (String field : List.of(
                "inputTokens",
                "outputTokens",
                "totalTokens",
                "cacheReadTokens",
                "cacheCreationTokens",
                "cacheCreation5mTokens",
                "cacheCreation1hTokens",
                "reasoningTokens")) {
            group.put(field, new Document("$sum", "$" + field));
        }
        var pipeline = List.of(
                new Document("$match", match),
                new Document("$group", group),
                new Document(
                        "$sort",
                        new Document("_id.provider", 1).append("_id.model", 1).append("_id.activity", 1)),
                new Document("$limit", 10001));
        var result = new ArrayList<UsageReports.Breakdown>();
        for (var row : mongo.getCollection(COLLECTION).aggregate(pipeline)) {
            result.add(breakdown(row));
        }
        if (result.size() > 10000) {
            throw new IllegalArgumentException("Too many price groups; narrow the reporting range");
        }
        return List.copyOf(result);
    }

    @Override
    public Set<String> sourceRequestKeys(String ownerAccountId, String rootExecutionId) {
        indexes();
        if (ownerAccountId == null || ownerAccountId.isBlank() || rootExecutionId == null
                || rootExecutionId.isBlank()) {
            throw new IllegalArgumentException("Task coverage requires trusted owner and task");
        }
        var match = new Document("ownerAccountId", ownerAccountId).append("rootExecutionId", rootExecutionId)
                .append("activity", new Document("$ne", "learning"))
                .append("sourceRequestKey", new Document("$type", "string"));
        var result = new LinkedHashSet<String>();
        for (var row : mongo.getCollection(COLLECTION).aggregate(
                List.of(
                        new Document("$match", match),
                        new Document("$group", new Document("_id", "$sourceRequestKey")),
                        new Document("$limit", 100001)))) {
            result.add(row.getString("_id"));
        }
        if (result.size() > 100000) {
            throw new IllegalArgumentException("Task accounting exceeds 100000 distinct sources");
        }
        return Set.copyOf(result);
    }

    private Document conditionalSum(String field) {
        return new Document("$sum", new Document("$cond", List.of(field, 1, 0)));
    }

    private UsageReports.Breakdown breakdown(Document row) {
        var key = row.get("_id", Document.class);
        var rates = key.get("rates", Document.class);
        var categories = new LinkedHashSet<String>();
        for (var values : row.getList("unsupportedCategories", List.class)) {
            for (var value : values) {
                categories.add((String) value);
            }
        }
        long priced = number(row, "pricedRequests"), unpriced = number(row, "unpricedRequests");
        String status = priced == 0
                ? unpriced > 0 ? "unpriced" : "unavailable"
                : number(row, "partialRequests") > 0 ? "partial" : "complete";
        return new UsageReports.Breakdown(
                key.getString("provider"),
                key.getString("model"),
                key.getString("activity"),
                rates == null ? null : UsageReports.Rates.of(rates.getString("version"), price(rates)),
                priced == 0 ? null : UsageReports.amount(decimal(row.get("estimatedUsd"))),
                status,
                number(row, "requests"),
                number(row, "inputTokens"),
                number(row, "outputTokens"),
                number(row, "totalTokens"),
                priced,
                unpriced,
                number(row, "incompleteRequests"),
                number(row, "cacheReadTokens"),
                number(row, "cacheCreationTokens"),
                number(row, "cacheCreation5mTokens"),
                number(row, "cacheCreation1hTokens"),
                number(row, "reasoningTokens"),
                List.copyOf(categories));
    }

    private Document encode(UsageRecord value) {
        var context = value.context();
        var usage = value.usage();
        var estimate = value.estimate();
        var rate = value.price();
        var doc = new Document("_id", value.requestId()).append("revision", value.revision())
                .append("ownerAccountId", context.ownerAccountId())
                .append("executionId", context.executionId())
                .append("rootExecutionId", context.rootExecutionId()).append("activity", context.activity())
                .append("sourceTaskId", context.sourceTaskId())
                .append("sourceRequestKey", context.sourceRequestKey())
                .append("startedAt", Date.from(value.startedAt())).append("provider", value.provider())
                .append("requestedModel", value.requestedModel()).append("model", value.model())
                .append("responseModelObserved", value.responseModelObserved())
                .append("pricingVersion", value.pricingVersion())
                .append("priceCatalog", value.priceCatalog().stream().map(this::price).toList())
                .append("complete", value.complete()).append("hasUsage", usage != null)
                .append("rates", rate == null ? null : price(rate).append("version", value.pricingVersion()))
                .append(
                        "estimatedUsd",
                        estimate.estimatedUsd() == null ? null : new Decimal128(estimate.estimatedUsd()))
                .append("priced", estimate.estimatedUsd() != null)
                .append("unpriced", missingPrice(usage, rate))
                .append(
                        "incomplete",
                        !value.complete() || usage == null || usage.incomplete()
                                || !usage.unsupportedCategories().isEmpty())
                .append("partial", !"complete".equals(estimate.status()))
                .append("usageIncomplete", usage != null && usage.incomplete())
                .append("unsupportedCategories", usage == null ? List.of() : usage.unsupportedCategories());
        long[] counts = usage == null
                ? new long[8]
                : new long[]{usage.inputTokens(), usage.outputTokens(), usage.totalTokens(),
                        usage.cacheReadTokens(), usage.cacheCreationTokens(), usage.cacheCreation5mTokens(),
                        usage.cacheCreation1hTokens(), usage.reasoningTokens()};
        String[] fields = {"inputTokens", "outputTokens", "totalTokens", "cacheReadTokens",
                "cacheCreationTokens", "cacheCreation5mTokens", "cacheCreation1hTokens", "reasoningTokens"};
        for (int i = 0; i < fields.length; i++) {
            doc.put(fields[i], counts[i]);
        }
        return doc;
    }

    private static boolean missingPrice(Usage usage, UsageProperties.Price rate) {
        return rate == null
                || usage != null && (usage.cacheReadTokens() > 0 && rate.cacheReadPerMillion() == null
                        || usage.cacheCreationTokens() - usage.cacheCreation5mTokens()
                                - usage.cacheCreation1hTokens() > 0 && rate.cacheCreationPerMillion() == null
                        || usage.cacheCreation5mTokens() > 0 && rate.cacheCreation5mPerMillion() == null
                        || usage.cacheCreation1hTokens() > 0 && rate.cacheCreation1hPerMillion() == null);
    }

    private UsageRecord decode(Document doc) {
        // A stored activity nobody contributes is corrupt data, exactly as before activities were contributed.
        activities.require(doc.getString("activity"));
        var context = new UsageLedger.Context(
                doc.getString("ownerAccountId"),
                doc.getString("executionId"),
                doc.getString("rootExecutionId"),
                doc.getString("activity"),
                doc.getString("sourceTaskId"),
                doc.getString("sourceRequestKey"));
        var usage = !doc.getBoolean("hasUsage")
                ? null
                : new Usage(
                        number(doc, "inputTokens"),
                        number(doc, "outputTokens"),
                        number(doc, "totalTokens"),
                        number(doc, "cacheReadTokens"),
                        number(doc, "cacheCreationTokens"),
                        number(doc, "cacheCreation5mTokens"),
                        number(doc, "cacheCreation1hTokens"),
                        number(doc, "reasoningTokens"),
                        doc.getBoolean("usageIncomplete"),
                        doc.getList("unsupportedCategories", String.class));
        // Older records did not distinguish requested fallback from a reported identity.
        // Preserve their existing model once usage or a different model was recorded.
        boolean responseModelObserved = doc.getBoolean(
                "responseModelObserved",
                usage != null || !Objects.equals(doc.getString("requestedModel"), doc.getString("model")));
        return new UsageRecord(
                doc.getString("_id"),
                context,
                doc.getDate("startedAt").toInstant(),
                doc.getString("provider"),
                doc.getString("requestedModel"),
                doc.getString("model"),
                responseModelObserved,
                doc.getString("pricingVersion"),
                doc.getList("priceCatalog", Document.class).stream().map(this::price).toList(),
                usage,
                doc.getBoolean("complete"),
                number(doc, "revision"));
    }

    private Document price(UsageProperties.Price price) {
        var doc = new Document("provider", price.provider()).append("model", price.model());
        String[] fields = {"inputPerMillion", "outputPerMillion", "cacheReadPerMillion",
                "cacheCreationPerMillion", "cacheCreation5mPerMillion", "cacheCreation1hPerMillion"};
        BigDecimal[] rates = {price.inputPerMillion(), price.outputPerMillion(), price.cacheReadPerMillion(),
                price.cacheCreationPerMillion(), price.cacheCreation5mPerMillion(),
                price.cacheCreation1hPerMillion()};
        for (int i = 0; i < fields.length; i++) {
            doc.put(fields[i], rates[i] == null ? null : new Decimal128(rates[i]));
        }
        if (price.longContext() != null) {
            var rule = price.longContext();
            doc.put(
                    "longContext",
                    new Document("inputTokenThreshold", rule.inputTokenThreshold())
                            .append("inputMultiplier", new Decimal128(rule.inputMultiplier()))
                            .append("outputMultiplier", new Decimal128(rule.outputMultiplier())));
        }
        return doc;
    }

    private UsageProperties.Price price(Document doc) {
        var rule = doc.get("longContext", Document.class);
        var longContext = rule == null
                ? null
                : new UsageProperties.LongContext(
                        number(rule, "inputTokenThreshold"),
                        decimal(rule.get("inputMultiplier")),
                        decimal(rule.get("outputMultiplier")));
        return new UsageProperties.Price(
                doc.getString("provider"),
                doc.getString("model"),
                decimal(doc.get("inputPerMillion")),
                decimal(doc.get("outputPerMillion")),
                decimal(doc.get("cacheReadPerMillion")),
                decimal(doc.get("cacheCreationPerMillion")),
                decimal(doc.get("cacheCreation5mPerMillion")),
                decimal(doc.get("cacheCreation1hPerMillion")),
                longContext);
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Decimal128 number
                ? number.bigDecimalValue()
                : new BigDecimal(value.toString());
    }

    private static long number(Document doc, String field) {
        return ((Number) doc.get(field)).longValue();
    }

    private void acknowledged() {
        if (!mongo.getCollection(COLLECTION).getWriteConcern().isAcknowledged()) {
            throw new IllegalStateException("Usage accounting requires acknowledged writes");
        }
    }

    private void indexes() {
        if (indexed) {
            return;
        }
        synchronized (this) {
            if (indexed) {
                return;
            }
            var collection = mongo.getCollection(COLLECTION);
            collection.createIndex(
                    new Document("ownerAccountId", 1).append("startedAt", 1).append("provider", 1)
                            .append("model", 1).append("activity", 1));
            collection.createIndex(
                    new Document("ownerAccountId", 1).append("rootExecutionId", 1).append("activity", 1));
            indexed = true;
        }
    }
}
