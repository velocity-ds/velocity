package net.velocity.agent.knowledge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import org.springframework.dao.DuplicateKeyException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import net.velocity.agent.JsonSchema;
import net.velocity.agent.ToolRegistry;
import net.velocity.inference.SystemPrompts;

/** Java owns identity and query validation; no tool argument can supply a storage boundary. */
public final class KnowledgeService {

    public static final int MAX_CONTENT_BYTES = 262_144;
    public static final int MAX_RESPONSE_CHARS = 20_000;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    private static final Set<String> METADATA_FIELDS = Set
            .of("dataset", "key", "title", "description", "tags", "revision", "created_at", "updated_at");
    private static final Set<String> SORT_FIELDS = Set
            .of("dataset", "key", "title", "revision", "created_at", "updated_at");
    private final KnowledgeRepository repository;

    public KnowledgeService(KnowledgeRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    /** The validated shape of a record about to be written; {@code expected} is the revision it replaces. */
    record Draft(
            String dataset,
            String key,
            String title,
            String description,
            List<String> tags,
            Map<String, Object> content,
            long expected) {}

    public Map<String, Object> save(ToolRegistry.Context context, Map<String, Object> input) {
        String owner = context.scope().requireOwnerAccountId();
        var draft = saveDraft(input);
        var existing = repository.findByKey(owner, draft.dataset(), draft.key());
        if (saveConflicts(draft, existing)) {
            return conflict(existing);
        }
        return persist(context, owner, draft, existing);
    }

    /**
     * Changes part of an existing record: {@code set} assigns content paths, {@code unset} removes them,
     * {@code append} adds to arrays, and title, description and tags are replaced when supplied. The merged
     * record is validated like a save and written only while the record is still at the expected revision.
     */
    public Map<String, Object> update(ToolRegistry.Context context, Map<String, Object> input) {
        String owner = context.scope().requireOwnerAccountId();
        long expected = number(input, "expected_revision", -1, 1, Long.MAX_VALUE - 1);
        var existing = locate(owner, input);
        if (existing.isEmpty()) {
            return result("not_found");
        }
        if (existing.get().revision() != expected) {
            return conflict(existing);
        }
        var draft = updateDraft(input, existing.get(), expected);
        return persist(context, owner, draft, existing);
    }

    /** Removes one record while it is still at the expected revision. */
    public Map<String, Object> delete(ToolRegistry.Context context, Map<String, Object> input) {
        String owner = context.scope().requireOwnerAccountId();
        long expected = number(input, "expected_revision", -1, 1, Long.MAX_VALUE - 1);
        var existing = locate(owner, input);
        if (existing.isEmpty()) {
            return result("not_found");
        }
        var record = existing.get();
        if (record.revision() != expected) {
            return conflict(existing);
        }
        context.cancellation().check();
        if (!repository.delete(owner, record.id(), expected)) {
            var current = repository.findById(owner, record.id());
            return current.isEmpty() ? result("not_found") : conflict(current);
        }
        var result = result("deleted");
        result.put("record", metadata(record));
        result.put("database_committed", true);
        return bounded(result);
    }

    /** What a pending {@code data_save} will do, for the requester's confirmation. */
    public String previewSave(ToolRegistry.Context context, Map<String, Object> input) {
        String owner = context.scope().requireOwnerAccountId();
        Draft draft;
        try {
            draft = saveDraft(input);
        } catch (InvalidRequestException invalid) {
            return KnowledgePreview.invalid("Save", invalid.getMessage());
        }
        var existing = repository.findByKey(owner, draft.dataset(), draft.key());
        if (existing.isEmpty()) {
            return draft.expected() == 0
                    ? KnowledgePreview.created(draft)
                    : KnowledgePreview.missing("Replace", draft.dataset(), draft.key(), null);
        }
        if (saveConflicts(draft, existing)) {
            return draft.expected() == 0
                    ? "Save record to dataset \"" + draft.dataset() + "\" (key " + draft.key()
                            + "): a record with this key already exists at revision "
                            + existing.get().revision()
                            + ", so the call will report a conflict and change nothing."
                    : KnowledgePreview.conflict("Replace", existing.get(), draft.expected());
        }
        return KnowledgePreview.replaced(existing.get(), draft, "Replace");
    }

    /** What a pending {@code data_update} will do, for the requester's confirmation. */
    public String previewUpdate(ToolRegistry.Context context, Map<String, Object> input) {
        String owner = context.scope().requireOwnerAccountId();
        try {
            long expected = number(input, "expected_revision", -1, 1, Long.MAX_VALUE - 1);
            var existing = locate(owner, input);
            if (existing.isEmpty()) {
                return KnowledgePreview.missing(
                        "Change",
                        (String) input.get("dataset"),
                        (String) input.get("key"),
                        (String) input.get("id"));
            }
            if (existing.get().revision() != expected) {
                return KnowledgePreview.conflict("Change", existing.get(), expected);
            }
            return KnowledgePreview
                    .replaced(existing.get(), updateDraft(input, existing.get(), expected), "Change");
        } catch (InvalidRequestException invalid) {
            return KnowledgePreview.invalid("Change", invalid.getMessage());
        }
    }

    /** What a pending {@code data_delete} will do, for the requester's confirmation. */
    public String previewDelete(ToolRegistry.Context context, Map<String, Object> input) {
        String owner = context.scope().requireOwnerAccountId();
        try {
            long expected = number(input, "expected_revision", -1, 1, Long.MAX_VALUE - 1);
            var existing = locate(owner, input);
            if (existing.isEmpty()) {
                return KnowledgePreview.missing(
                        "Delete",
                        (String) input.get("dataset"),
                        (String) input.get("key"),
                        (String) input.get("id"));
            }
            if (existing.get().revision() != expected) {
                return KnowledgePreview.conflict("Delete", existing.get(), expected);
            }
            return KnowledgePreview.deleted(existing.get());
        } catch (InvalidRequestException invalid) {
            return KnowledgePreview.invalid("Delete", invalid.getMessage());
        }
    }

    private static Draft saveDraft(Map<String, Object> input) {
        String dataset = name(input.get("dataset"), 64), key = name(input.get("key"), 128);
        long expected = number(input, "expected_revision", -1, 0, Long.MAX_VALUE - 1);
        return new Draft(
                dataset,
                key,
                text(input.get("title"), 1, 200),
                text(input.get("description"), 0, 1000),
                strings(input.get("tags"), 20, 64),
                normalize(object(input.get("content"))),
                expected);
    }

    private static boolean saveConflicts(Draft draft, Optional<KnowledgeRecord> existing) {
        return draft.expected() == 0 && existing.isPresent()
                || draft.expected() > 0
                        && (existing.isEmpty() || existing.get().revision() != draft.expected());
    }

    private static Draft updateDraft(Map<String, Object> input, KnowledgeRecord existing, long expected)
            throws InvalidRequestException {
        Map<String, Object> content;
        try {
            // A mutable deep copy; the stored content is immutable.
            content = JSON.readValue(
                    json(existing.content()),
                    new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (IOException failure) {
            throw new IllegalStateException("Stored content cannot be decoded", failure);
        }
        int changes = 0;
        if (input.containsKey("set")) {
            var assignments = object(input.get("set"));
            if (assignments.isEmpty() || assignments.size() > 50) {
                throw invalid("set accepts 1 to 50 content paths");
            }
            for (var assignment : assignments.entrySet()) {
                String[] path = contentPath(assignment.getKey());
                parent(content, path, true).put(path[path.length - 1], assignment.getValue());
                changes++;
            }
        }
        if (input.containsKey("append")) {
            var additions = object(input.get("append"));
            if (additions.isEmpty() || additions.size() > 50) {
                throw invalid("append accepts 1 to 50 content paths");
            }
            for (var addition : additions.entrySet()) {
                String[] path = contentPath(addition.getKey());
                var holder = parent(content, path, true);
                Object current = holder.get(path[path.length - 1]);
                List<Object> items;
                if (current == null) {
                    items = new ArrayList<>();
                } else if (current instanceof List<?> list) {
                    items = new ArrayList<>(list);
                } else {
                    throw invalid("append requires an array or absent value at " + addition.getKey());
                }
                items.add(addition.getValue());
                holder.put(path[path.length - 1], items);
                changes++;
            }
        }
        if (input.containsKey("unset")) {
            var removals = strings(input.get("unset"), 50, 128);
            if (removals.isEmpty()) {
                throw invalid("unset accepts 1 to 50 content paths");
            }
            for (String removal : removals) {
                String[] path = contentPath(removal);
                var holder = parent(content, path, false);
                if (holder != null && holder.containsKey(path[path.length - 1])) {
                    holder.remove(path[path.length - 1]);
                    changes++;
                }
            }
        }
        String title = input.containsKey("title") ? text(input.get("title"), 1, 200) : existing.title();
        String description = input.containsKey("description")
                ? text(input.get("description"), 0, 1000)
                : existing.description();
        List<String> tags = input.containsKey("tags") ? strings(input.get("tags"), 20, 64) : existing.tags();
        if (changes == 0 && title.equals(existing.title()) && description.equals(existing.description())
                && tags.equals(existing.tags())) {
            throw invalid("No change requested; supply set, append, unset, title, description or tags");
        }
        return new Draft(
                existing.dataset(),
                existing.key(),
                title,
                description,
                tags,
                normalize(content),
                expected);
    }

    private Map<String, Object> persist(
            ToolRegistry.Context context,
            String owner,
            Draft draft,
            Optional<KnowledgeRecord> existing) {
        var observed = new TreeSet<String>();
        observe(draft.content(), "content", observed);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        if (existing.isPresent() && !now.isAfter(existing.get().updatedAt())) {
            now = existing.get().updatedAt().plusMillis(1);
        }
        var record = new KnowledgeRecord(
                existing.map(KnowledgeRecord::id).orElseGet(() -> UUID.randomUUID().toString()),
                owner,
                draft.dataset(),
                draft.key(),
                draft.title(),
                draft.description(),
                draft.tags(),
                draft.content(),
                draft.expected() + 1,
                existing.map(KnowledgeRecord::createdAt).orElse(now),
                now,
                existing.map(KnowledgeRecord::createdByExecutionId).orElse(context.executionId()),
                context.executionId(),
                List.copyOf(observed));
        if (json(metadata(record)).length() > 12_000) {
            throw invalid("Discovery metadata exceeds 12000 serialized characters");
        }
        context.cancellation().check();
        try {
            if (draft.expected() == 0) {
                repository.insert(record);
            } else if (!repository.replace(record, draft.expected())) {
                return conflict(repository.findByKey(owner, draft.dataset(), draft.key()));
            }
        } catch (DuplicateKeyException duplicate) {
            return conflict(repository.findByKey(owner, draft.dataset(), draft.key()));
        }
        var result = result("saved");
        result.put("record", metadata(record));
        result.put("database_committed", true);
        return bounded(result);
    }

    /** Checks, bounds, detaches and normalizes content through the JSON representation used on reads. */
    private static Map<String, Object> normalize(Map<String, Object> content) {
        JsonSchema.validate(Map.of("type", "object"), content);
        String encoded = json(content);
        if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES) {
            throw invalid("Content exceeds 262144 UTF-8 bytes");
        }
        validateContent(content, 0);
        Map<String, Object> normalized;
        try {
            normalized = JSON.readValue(encoded, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (IOException failure) {
            throw invalid("Cannot encode JSON");
        }
        validateContent(normalized, 0);
        return normalized;
    }

    private static String[] contentPath(String path) {
        if (path == null || path.length() > 128
                || !path.matches("content\\.[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*")) {
            throw invalid(
                    "Unsupported content path; use content.<field> with letters, digits, underscore or hyphen");
        }
        return path.substring("content.".length()).split("\\.");
    }

    /** The object holding the path's last segment, created on the way when asked; null when absent. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parent(Map<String, Object> content, String[] path, boolean create) {
        Map<String, Object> current = content;
        for (int index = 0; index < path.length - 1; index++) {
            Object next = current.get(path[index]);
            if (next instanceof Map<?, ?> map) {
                current = (Map<String, Object>) map;
            } else if (next == null && create) {
                var child = new LinkedHashMap<String, Object>();
                current.put(path[index], child);
                current = child;
            } else if (next == null) {
                return null;
            } else {
                throw invalid(
                        "Content path content." + String.join(".", path) + " crosses a non-object value");
            }
        }
        return current;
    }

    /** Resolves {@code id} or {@code dataset} + {@code key}, never both. */
    private Optional<KnowledgeRecord> locate(String owner, Map<String, Object> input) {
        boolean byId = input.containsKey("id");
        if (byId && (input.containsKey("dataset") || input.containsKey("key"))
                || !byId && !(input.containsKey("dataset") && input.containsKey("key"))) {
            throw invalid("Specify id OR dataset and key");
        }
        return byId
                ? repository.findById(owner, text(input.get("id"), 1, 64))
                : repository.findByKey(owner, name(input.get("dataset"), 64), name(input.get("key"), 128));
    }

    public Map<String, Object> get(ToolRegistry.Context context, Map<String, Object> input) {
        String owner = context.scope().requireOwnerAccountId();
        var found = locate(owner, input);
        if (found.isEmpty()) {
            return result("not_found");
        }
        var record = found.get();
        if (input.containsKey("expected_revision")
                && number(input, "expected_revision", 0, 1, Long.MAX_VALUE) != record.revision()) {
            return conflict(found);
        }
        var result = result("found");
        result.put("record", metadata(record));
        result.put("content", record.content());
        result.put("content_trust", "untrusted_data");
        if (!input.containsKey("offset") && !input.containsKey("limit")
                && json(result).length() <= MAX_RESPONSE_CHARS) {
            return result;
        }
        result.remove("content");
        String content = json(record.content());
        int offset = (int) number(input, "offset", 0, 0, 2_000_000);
        if (offset > 0 && !input.containsKey("expected_revision")) {
            throw invalid("Subsequent chunks require expected_revision");
        }
        if (offset > content.length()) {
            throw invalid("Offset exceeds content length");
        }
        int limit = (int) number(input, "limit", 4000, 1, 4000);
        int end = Math.min(content.length(), offset + limit);
        // Offsets are UTF-16 code units; preserve surrogate pairs at automatically selected boundaries.
        if (offset > 0 && offset < content.length() && Character.isLowSurrogate(content.charAt(offset))
                && Character.isHighSurrogate(content.charAt(offset - 1))) {
            throw invalid("Offset splits a Unicode character");
        }
        if (end < content.length() && Character.isHighSurrogate(content.charAt(end - 1))) {
            end++;
        }
        result.put("format", "json_text");
        result.put("offset", offset);
        result.put("total_chars", content.length());
        result.put("retrieval", SystemPrompts.KNOWLEDGE_CHUNK_RETRIEVAL_INSTRUCTIONS);
        do {
            result.put("content_chunk", content.substring(offset, end));
            result.put("next_offset", end < content.length() ? end : -1);
            result.put("complete", end == content.length());
            if (json(result).length() <= MAX_RESPONSE_CHARS) {
                return result;
            }
            end--;
            if (end > offset && Character.isHighSurrogate(content.charAt(end - 1))) {
                end--;
            }
        } while (end > offset);
        throw new IllegalStateException("Record metadata exceeds response limit");
    }

    public Map<String, Object> query(ToolRegistry.Context context, Map<String, Object> input) {
        String dataset = name(input.get("dataset"), 64);
        var filters = filters(input.getOrDefault("filters", Map.of()));
        var fields = strings(input.getOrDefault("fields", List.of()), 20, 128);
        fields.forEach(KnowledgeService::field);
        String sort = (String) input.getOrDefault("sort_by", "updated_at");
        if (!SORT_FIELDS.contains(sort)) {
            throw invalid("Unsupported sort field");
        }
        int offset = (int) number(input, "offset", 0, 0, 1_000_000),
                limit = (int) number(input, "limit", 20, 1, 100);
        var records = repository.query(
                context.scope().requireOwnerAccountId(),
                new KnowledgeQuery(
                        dataset,
                        filters,
                        sort,
                        bool(input.getOrDefault("descending", true)),
                        offset,
                        limit + 1,
                        null));
        return page(records, offset, limit, fields);
    }

    /**
     * A read-only count, sum, average, minimum or maximum over the records of one dataset that the same filters
     * as {@link #query} match, optionally grouped by one field. Records whose field is missing or not a number
     * are skipped and counted in {@code skipped_non_numeric}.
     */
    public Map<String, Object> aggregate(ToolRegistry.Context context, Map<String, Object> input) {
        String dataset = name(input.get("dataset"), 64);
        var filters = filters(input.getOrDefault("filters", Map.of()));
        String operation = text(input.get("operation"), 1, 16);
        if (!KnowledgeAggregation.OPERATIONS.contains(operation)) {
            throw invalid("Unsupported operation; use count, sum, avg, min or max");
        }
        boolean counting = operation.equals("count");
        String field = null;
        if (input.containsKey("field")) {
            field = text(input.get("field"), 1, 128);
            contentPath(field);
        }
        if (counting && field != null) {
            throw invalid("count takes no field; omit it");
        }
        if (!counting && field == null) {
            throw invalid(operation + " requires a numeric content.<path> field");
        }
        String groupBy = null;
        if (input.containsKey("group_by")) {
            groupBy = text(input.get("group_by"), 1, 128);
            field(groupBy);
        }
        if (groupBy == null && input.containsKey("group_limit")) {
            throw invalid("group_limit requires group_by");
        }
        int groupLimit = (int) number(input, "group_limit", 20, 1, KnowledgeAggregation.MAX_GROUPS);
        var outcome = repository.aggregate(
                context.scope().requireOwnerAccountId(),
                new KnowledgeAggregation(dataset, filters, operation, field, groupBy, groupLimit));
        var result = result("ok");
        result.put("dataset", dataset);
        result.put("operation", operation);
        if (field != null) {
            result.put("field", field);
        }
        result.put("count", outcome.count());
        result.put("skipped_non_numeric", outcome.skippedNonNumeric());
        if (groupBy == null) {
            Object value = outcome.groups().isEmpty()
                    ? (counting ? 0L : null)
                    : outcome.groups().getFirst().value();
            if (value != null) {
                result.put("value", value);
            }
            return bounded(result);
        }
        result.put("group_by", groupBy);
        var groups = new ArrayList<Map<String, Object>>();
        for (var group : outcome.groups()) {
            var entry = new LinkedHashMap<String, Object>();
            if (group.key() instanceof String key) {
                entry.put("group", key);
            } else if (group.key() != null) {
                entry.put("group", json(group.key()));
            }
            entry.put("count", group.count());
            if (group.value() != null) {
                entry.put("value", group.value());
            }
            entry.put("skipped_non_numeric", group.skippedNonNumeric());
            groups.add(entry);
        }
        result.put("groups", groups);
        result.put("group_limit_reached", outcome.limitReached());
        return bounded(result);
    }

    public Map<String, Object> catalog(ToolRegistry.Context context, Map<String, Object> input) {
        String owner = context.scope().requireOwnerAccountId();
        String mode = (String) input.getOrDefault("mode", "records");
        String search = input.containsKey("search") ? text(input.get("search"), 1, 100) : null;
        int limit = (int) number(input, "limit", 20, 1, 100);
        if ("datasets".equals(mode)) {
            if (input.containsKey("offset") || input.containsKey("dataset")) {
                throw invalid("Dataset mode uses after; omit offset and dataset");
            }
            String after = input.containsKey("after") ? name(input.get("after"), 64) : null;
            var datasets = repository.datasets(owner, after, limit + 1, search);
            var entries = new ArrayList<Map<String, Object>>();
            var result = result("ok");
            result.put("datasets", entries);
            for (String dataset : datasets) {
                if (entries.size() == limit) {
                    break;
                }
                var latest = repository
                        .query(owner, new KnowledgeQuery(dataset, List.of(), "updated_at", true, 0, 1, null));
                var entry = new LinkedHashMap<String, Object>();
                entry.put("dataset", dataset);
                entry.put("location", "knowledge://" + owner + "/" + dataset);
                entry.put("schema_status", "observed_only");
                entry.put("observation_scope", "latest_record");
                entry.put("retrieval", SystemPrompts.KNOWLEDGE_DATASET_RETRIEVAL_INSTRUCTIONS);
                if (!latest.isEmpty()) {
                    entry.put("sample_record", metadata(latest.getFirst()));
                }
                entries.add(entry);
                if (json(result).length() > MAX_RESPONSE_CHARS - 1000) {
                    entries.removeLast();
                    break;
                }
            }
            boolean more = datasets.size() > entries.size();
            result.put("has_more", more);
            if (more && !entries.isEmpty()) {
                result.put("next_after", entries.getLast().get("dataset"));
            }
            return bounded(result);
        }
        if (!"records".equals(mode) || input.containsKey("after")) {
            throw invalid("Record mode uses offset; omit after");
        }
        String dataset = input.containsKey("dataset") ? name(input.get("dataset"), 64) : null;
        int offset = (int) number(input, "offset", 0, 0, 1_000_000);
        var records = repository.query(
                owner,
                new KnowledgeQuery(dataset, List.of(), "updated_at", true, offset, limit + 1, search));
        return page(records, offset, limit, List.of());
    }

    private Map<String, Object> page(
            List<KnowledgeRecord> records,
            int offset,
            int limit,
            List<String> fields) {
        var entries = new ArrayList<Map<String, Object>>();
        var result = result("ok");
        result.put("records", entries);
        for (var record : records) {
            if (entries.size() == limit) {
                break;
            }
            var entry = metadata(record);
            if (!fields.isEmpty()) {
                var selected = new LinkedHashMap<String, Object>();
                for (String field : fields) {
                    Object value = field.startsWith("content.")
                            ? at(record.content(), field.substring(8))
                            : entry.get(field);
                    selected.put(field, value);
                }
                entry.put("selected_fields", selected);
                if (json(entry).length() > MAX_RESPONSE_CHARS - 2000) {
                    entry.remove("selected_fields");
                    entry.put("selected_fields_omitted", true);
                }
            }
            entries.add(entry);
            if (json(result).length() > MAX_RESPONSE_CHARS - 1000) {
                entries.removeLast();
                break;
            }
        }
        result.put("offset", offset);
        result.put("has_more", records.size() > entries.size());
        if (records.size() > entries.size()) {
            if (offset + entries.size() <= 1_000_000) {
                result.put("next_offset", offset + entries.size());
            } else {
                result.put("pagination_limit_reached", true);
            }
        }
        result.put("retrieval", SystemPrompts.KNOWLEDGE_RECORD_RETRIEVAL_INSTRUCTIONS);
        return bounded(result);
    }

    public static LinkedHashMap<String, Object> metadata(KnowledgeRecord record) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", record.id());
        result.put("dataset", record.dataset());
        result.put("key", record.key());
        result.put("title", record.title());
        result.put("description", record.description());
        result.put("tags", record.tags());
        result.put(
                "location",
                "knowledge://" + record.ownerAccountId() + "/" + record.dataset() + "/" + record.key());
        result.put("revision", record.revision());
        result.put("created_at", record.createdAt().toString());
        result.put("updated_at", record.updatedAt().toString());
        result.put("created_by_execution_id", record.createdByExecutionId());
        result.put("updated_by_execution_id", record.updatedByExecutionId());
        result.put("observed_fields", record.observedFields());
        result.put("observed_fields_complete", false);
        result.put("schema_status", "observed_only");
        return result;
    }

    private static Map<String, Object> conflict(Optional<KnowledgeRecord> existing) {
        var result = result("revision_conflict");
        existing.ifPresent(record -> {
            result.put("id", record.id());
            result.put("current_revision", record.revision());
        });
        result.put("message", SystemPrompts.KNOWLEDGE_REVISION_CONFLICT_INSTRUCTIONS);
        return result;
    }

    private static List<KnowledgeQuery.Filter> filters(Object input) {
        var object = object(input);
        if (object.size() > 10) {
            throw invalid("At most 10 filters");
        }
        var result = new ArrayList<KnowledgeQuery.Filter>();
        object.forEach((path, condition) -> {
            field(path);
            var operators = object(condition);
            // One operator, or a range: one lower bound (gt or gte) with one upper bound (lt or lte).
            boolean range = operators.size() == 2
                    && operators.keySet().stream().filter(Set.of("gt", "gte")::contains).count() == 1
                    && operators.keySet().stream().filter(Set.of("lt", "lte")::contains).count() == 1;
            if (operators.size() != 1 && !range) {
                throw invalid("One operator per filter field, or a range of one lower and one upper bound");
            }
            operators.forEach((operator, value) -> {
                switch (operator) {
                    case "eq" -> scalar(value);
                    case "gt", "gte", "lt", "lte" -> {
                        if (!(value instanceof String || value instanceof Number)) {
                            throw invalid("Comparison requires string or number");
                        }
                        scalar(value);
                    }
                    case "in" -> {
                        if (!(value instanceof List<?> values) || values.isEmpty() || values.size() > 20) {
                            throw invalid("in requires 1 to 20 scalars");
                        }
                        values.forEach(KnowledgeService::scalar);
                    }
                    case "exists" -> bool(value);
                    case "contains" -> {
                        if (path.equals("tags")) {
                            text(value, 1, 64);
                        } else if (path.startsWith("content.")) {
                            text(value, 1, 100);
                        } else {
                            throw invalid("contains is supported for tags and content fields");
                        }
                    }
                    default -> throw invalid("Unsupported filter operator");
                }
                result.add(new KnowledgeQuery.Filter(path, operator, value));
            });
        });
        return List.copyOf(result);
    }

    private static void scalar(Object value) {
        if (!(value == null || value instanceof String || value instanceof Number
                || value instanceof Boolean)) {
            throw invalid("Expected scalar filter value");
        }
        if (value instanceof String string && string.length() > 1000) {
            throw invalid("Filter value too long");
        }
    }

    private static void field(String value) {
        if (!METADATA_FIELDS.contains(value) && !(value.length() <= 128
                && value.matches("content\\.[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*"))) {
            throw invalid("Unsupported field path");
        }
    }

    private static Object at(Map<String, Object> content, String path) {
        return at(content, path.split("\\."), 0);
    }

    private static Object at(Object value, String[] path, int index) {
        if (index == path.length) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            return at(map.get(path[index]), path, index + 1);
        }
        if (value instanceof List<?> list) {
            if (path[index].matches("[0-9]+")) {
                try {
                    int position = Integer.parseInt(path[index]);
                    return position < list.size() ? at(list.get(position), path, index + 1) : null;
                } catch (NumberFormatException outOfRange) {
                    return null;
                }
            }
            return list.stream().map(item -> at(item, path, index)).toList();
        }
        return null;
    }

    private static void validateContent(Object value, int depth) {
        if (depth > 16) {
            throw invalid("Content exceeds nesting limit 16");
        }
        if (value instanceof Map<?, ?> object) {
            for (var entry : object.entrySet()) {
                String key = (String) entry.getKey();
                if (key.isEmpty() || key.length() > 128 || key.contains(".") || key.startsWith("$")
                        || key.indexOf(0) >= 0) {
                    throw invalid(
                            "Content keys must be 1 to 128 characters without dots, NUL or leading dollar signs");
                }
                validateContent(entry.getValue(), depth + 1);
            }
        } else if (value instanceof List<?> list) {
            list.forEach(item -> validateContent(item, depth + 1));
        } else if (value instanceof java.math.BigDecimal || value instanceof java.math.BigInteger) {
            try {
                new org.bson.types.Decimal128(
                        value instanceof java.math.BigDecimal decimal
                                ? decimal
                                : new java.math.BigDecimal((java.math.BigInteger) value));
            } catch (ArithmeticException | NumberFormatException failure) {
                throw invalid("JSON number exceeds MongoDB Decimal128 precision or range");
            }
        }
    }

    private static void observe(Map<String, Object> content, String prefix, Set<String> fields) {
        for (var entry : new TreeMap<>(content).entrySet()) {
            String path = prefix + "." + entry.getKey();
            if (fields.size() >= 64) {
                return;
            }
            if (path.length() <= 128 && path.matches("content\\.[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*")) {
                fields.add(path);
            }
            if (entry.getValue() instanceof Map<?, ?> child) {
                observe(object(child), path, fields);
            }
        }
    }

    private static String name(Object value, int max) {
        String text = text(value, 1, max);
        if (!text.matches("[A-Za-z0-9][A-Za-z0-9_-]*")) {
            throw invalid("Invalid dataset or key; use letters, digits, underscore or hyphen");
        }
        return text;
    }

    private static String text(Object value, int min, int max) {
        if (!(value instanceof String text) || text.length() < min || text.length() > max) {
            throw invalid("Invalid string length");
        }
        return text;
    }

    private static List<String> strings(Object value, int max, int length) {
        if (!(value instanceof List<?> list) || list.size() > max) {
            throw invalid("Invalid list length");
        }
        return list.stream().map(item -> text(item, 1, length)).distinct().toList();
    }

    private static boolean bool(Object value) {
        if (!(value instanceof Boolean bool)) {
            throw invalid("Expected boolean");
        }
        return bool;
    }

    private static long number(
            Map<String, Object> input,
            String field,
            long defaultValue,
            long min,
            long max) {
        Object value = input.getOrDefault(field, defaultValue);
        try {
            if (!(value instanceof Number number)) {
                throw invalid("Expected integer");
            }
            long result = new java.math.BigDecimal(number.toString()).longValueExact();
            if (result < min || result > max) {
                throw invalid("Integer outside limits");
            }
            return result;
        } catch (ArithmeticException failure) {
            throw invalid("Expected bounded integer");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)
                || map.keySet().stream().anyMatch(key -> !(key instanceof String))) {
            throw invalid("Expected JSON object");
        }
        return (Map<String, Object>) map;
    }

    static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (IOException failure) {
            throw invalid("Cannot encode JSON");
        }
    }

    private static LinkedHashMap<String, Object> result(String status) {
        var result = new LinkedHashMap<String, Object>();
        result.put("status", status);
        return result;
    }

    private static Map<String, Object> bounded(Map<String, Object> result) {
        if (json(result).length() > MAX_RESPONSE_CHARS) {
            throw new IllegalStateException("Response exceeds limit");
        }
        return result;
    }

    /** Validation known to precede any database mutation; safe to report without write uncertainty. */
    public static final class InvalidRequestException extends IllegalArgumentException {

        private InvalidRequestException(String message) {
            super(message);
        }
    }

    private static InvalidRequestException invalid(String message) {
        return new InvalidRequestException(message);
    }
}
