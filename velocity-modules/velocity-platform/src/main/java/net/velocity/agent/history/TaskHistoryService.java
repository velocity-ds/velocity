package net.velocity.agent.history;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.json.JsonMapper;

import net.velocity.agent.ToolRegistry;
import net.velocity.agent.persistence.ExecutionRepository;
import net.velocity.agent.persistence.NotFoundException;
import net.velocity.agent.state.ExecutionSnapshot;
import net.velocity.agent.state.ExecutionStatus;
import net.velocity.inference.objects.InferenceContent;
import net.velocity.inference.objects.InferenceMessage;

/** Read-only, bounded access to prior executions within the caller's trusted identity. */
public final class TaskHistoryService {

    public static final int MAX_PAGE = 20;
    public static final int MAX_CHUNK = 4000;
    public static final int MAX_RESPONSE_CHARS = 20_000;
    private static final JsonMapper JSON = new JsonMapper();
    private final ExecutionRepository repository;
    private final HistoryProperties properties;
    private final HistoryProjection projection;

    public TaskHistoryService(
            ExecutionRepository repository,
            HistoryProperties properties,
            HistoryProjection projection) {
        this.repository = Objects.requireNonNull(repository);
        this.properties = Objects.requireNonNull(properties);
        this.projection = projection;
    }

    public boolean searchEnabled() {
        return properties.searchEnabled() && projection != null;
    }

    /** The canonical generation is durable indexing intent; failures never mutate canonical history. */
    public synchronized int projectPending() {
        if (!searchEnabled()) {
            return 0;
        }
        int projected = 0;
        for (ExecutionSnapshot snapshot : repository.list()) {
            if (projected == properties.maximumIndexedExecutionsPerPass()) {
                break;
            }
            if (projection.generation(snapshot.executionId) >= snapshot.historyGeneration) {
                continue;
            }
            var messages = new ArrayList<HistoryProjection.Message>();
            int total = 0;
            for (int sequence = 0; sequence < snapshot.messages.size() && total < 1_000_000; sequence++) {
                String full = messageText(snapshot.messages.get(sequence));
                String text = preview(
                        full,
                        Math.min(properties.maximumIndexedMessageChars(), 1_000_000 - total));
                total += text.length();
                messages.add(
                        new HistoryProjection.Message(
                                snapshot.scope.requireOwnerAccountId(),
                                snapshot.executionId,
                                snapshot.messageMetadata.get(sequence).messageId(),
                                sequence,
                                snapshot.historyGeneration,
                                text,
                                text.length() < full.length()));
            }
            // A late projector never revives a deleted source; projection has its own permanent tombstone too.
            try {
                ExecutionSnapshot current = repository.get(snapshot.executionId);
                if (current.historyGeneration != snapshot.historyGeneration) {
                    continue;
                }
                projection.replace(snapshot.executionId, snapshot.historyGeneration, messages);
                projected++;
            } catch (NotFoundException deleted) {
                projection.delete(snapshot.executionId);
            }
        }
        return projected;
    }

    public synchronized void deleteExecution(String executionId) {
        if (searchEnabled()) {
            projection.delete(executionId);
        }
    }

    public Map<String, Object> search(ToolRegistry.Context context, Map<String, Object> input) {
        fields(input, Set.of("query", "offset", "limit"));
        String query = text(input, "query", 200);
        int offset = (int) number(input, "offset", 0, 0, 1_000_000);
        int limit = (int) number(input, "limit", 10, 1, MAX_PAGE);
        if (!searchEnabled()) {
            return result("disabled");
        }
        context.cancellation().check();
        boolean complete = true;
        try {
            projectPending();
        } catch (RuntimeException unavailable) {
            complete = false;
        }
        var hits = projection.search(context.scope().requireOwnerAccountId(), query, offset, limit + 1);
        var matches = new ArrayList<Map<String, Object>>();
        int consumed = 0;
        for (var hit : hits.stream().limit(limit).toList()) {
            context.cancellation().check();
            ExecutionSnapshot snapshot;
            try {
                snapshot = repository.get(hit.executionId());
            } catch (NotFoundException deleted) {
                consumed++;
                continue;
            }
            if (!visible(context, snapshot) || hit.sequence() >= snapshot.messages.size()
                    || !hit.messageId().equals(snapshot.messageMetadata.get(hit.sequence()).messageId())) {
                consumed++;
                continue;
            }
            String full = messageText(snapshot.messages.get(hit.sequence()));
            int index = full.toLowerCase(java.util.Locale.ROOT)
                    .indexOf(query.toLowerCase(java.util.Locale.ROOT));
            if (index < 0) {
                consumed++;
                continue;
            }
            var entry = message(snapshot, hit.sequence(), 1200, Math.max(0, index - 200));
            entry.put("indexed_truncated", hit.truncated());
            if (encodedLength(matches) + encodedLength(entry) + 512 > MAX_RESPONSE_CHARS) {
                break;
            }
            matches.add(entry);
            consumed++;
        }
        var result = result("ok");
        result.put("messages", matches);
        result.put("next_offset", hits.size() > consumed ? offset + consumed : -1);
        result.put("coverage", complete ? "bounded_lexical_projection" : "projection_recovery_pending");
        result.put("maximum_indexed_message_chars", properties.maximumIndexedMessageChars());
        result.put("maximum_indexed_execution_chars", 1_000_000);
        result.put("native_block_coverage", "portable_text_and_portable_tool_relationships_only");
        return result;
    }

    public Map<String, Object> messages(ToolRegistry.Context context, Map<String, Object> input) {
        return readMessages(context, input, false);
    }

    public Map<String, Object> window(ToolRegistry.Context context, Map<String, Object> input) {
        return readMessages(context, input, true);
    }

    private Map<String, Object> readMessages(
            ToolRegistry.Context context,
            Map<String, Object> input,
            boolean window) {
        fields(
                input,
                window
                        ? Set.of("execution_id", "message_id", "radius", "expected_revision")
                        : Set.of("execution_id", "offset", "limit", "expected_revision"));
        String id = text(input, "execution_id", 128);
        long expected = number(input, "expected_revision", -1, 1, Long.MAX_VALUE);
        int offset = (int) number(input, "offset", 0, 0, 1_000_000);
        int limit = (int) number(input, "limit", 10, 1, MAX_PAGE);
        if (offset > 0 && expected == -1) {
            throw invalid("Subsequent pages require expected_revision");
        }
        context.cancellation().check();
        ExecutionSnapshot snapshot;
        try {
            snapshot = repository.get(id);
        } catch (NotFoundException missing) {
            return result("not_found");
        }
        if (!visible(context, snapshot)) {
            return result("not_found");
        }
        if (expected != -1 && expected != snapshot.revision) {
            var result = result("revision_conflict");
            result.put("current_revision", snapshot.revision);
            return result;
        }
        if (window) {
            String messageId = text(input, "message_id", 128);
            int sequence = -1;
            for (int i = 0; i < snapshot.messageMetadata.size(); i++) {
                if (snapshot.messageMetadata.get(i).messageId().equals(messageId)) {
                    sequence = i;
                    break;
                }
            }
            if (sequence == -1) {
                return result("not_found");
            }
            int radius = (int) number(input, "radius", 2, 0, 9);
            offset = Math.max(0, sequence - radius);
            limit = Math.min(MAX_PAGE, sequence + radius + 1 - offset);
        }
        var page = new ArrayList<Map<String, Object>>();
        int end = Math.min(snapshot.messages.size(), offset + limit);
        int bytes = 512;
        for (int sequence = offset; sequence < end; sequence++) {
            var item = message(snapshot, sequence, 3000, 0);
            int size = encodedLength(item);
            if (bytes + size > MAX_RESPONSE_CHARS) {
                break;
            }
            page.add(item);
            bytes += size;
        }
        var result = result("found");
        result.put("execution_id", id);
        result.put("revision", snapshot.revision);
        result.put("history_generation", snapshot.historyGeneration);
        result.put("messages", page);
        result.put("offset", offset);
        result.put("total_messages", snapshot.messages.size());
        result.put(
                "next_offset",
                offset + page.size() < snapshot.messages.size() ? offset + page.size() : -1);
        result.put("coverage", "portable_text_and_tool_relationships; native_content_retained_in_execution");
        return result;
    }

    private static LinkedHashMap<String, Object> message(
            ExecutionSnapshot snapshot,
            int sequence,
            int chars,
            int offset) {
        var metadata = snapshot.messageMetadata.get(sequence);
        var nativeMessage = snapshot.messages.get(sequence);
        String full = messageText(nativeMessage);
        if (offset > 0 && offset < full.length() && Character.isLowSurrogate(full.charAt(offset))
                && Character.isHighSurrogate(full.charAt(offset - 1))) {
            offset--;
        }
        var item = new LinkedHashMap<String, Object>();
        item.put("execution_id", snapshot.executionId);
        item.put("message_id", metadata.messageId());
        item.put("sequence", sequence);
        item.put("role", metadata.role());
        if (metadata.timestamp() != null) {
            item.put("timestamp", metadata.timestamp().toString());
        }
        if (metadata.source() != null) {
            item.put(
                    "source",
                    Map.of(
                            "workspace_id",
                            metadata.source().workspaceId(),
                            "channel_id",
                            metadata.source().channelId(),
                            "thread_id",
                            metadata.source().threadId(),
                            "message_id",
                            metadata.source().messageId()));
        }
        String chunk = preview(full.substring(Math.min(offset, full.length())), chars);
        item.put("excerpt", chunk);
        item.put("excerpt_offset", offset);
        item.put("truncated", offset > 0 || chunk.length() < full.length());
        item.put(
                "call_ids",
                nativeMessage.content().stream().filter(InferenceContent.ToolCall.class::isInstance)
                        .map(InferenceContent.ToolCall.class::cast).map(InferenceContent.ToolCall::id)
                        .toList());
        item.put(
                "result_call_ids",
                nativeMessage.content().stream().filter(InferenceContent.ToolResult.class::isInstance)
                        .map(InferenceContent.ToolResult.class::cast)
                        .map(InferenceContent.ToolResult::toolUseId).toList());
        return item;
    }

    public static String messageText(InferenceMessage message) {
        var text = new StringBuilder();
        appendText(message.content(), text);
        return text.toString();
    }

    private static void appendText(List<InferenceContent> content, StringBuilder text) {
        for (var block : content) {
            if (block instanceof InferenceContent.Text item) {
                text.append(item.text()).append('\n');
            } else if (block instanceof InferenceContent.ToolResult result) {
                appendText(result.content(), text);
            }
        }
    }

    public Map<String, Object> list(ToolRegistry.Context context, Map<String, Object> input) {
        fields(input, Set.of("status", "offset", "limit"));
        int offset = (int) number(input, "offset", 0, 0, 1_000_000);
        int limit = (int) number(input, "limit", 10, 1, MAX_PAGE);
        ExecutionStatus status = null;
        if (input.containsKey("status")) {
            try {
                status = ExecutionStatus.valueOf(text(input, "status", 32));
            } catch (IllegalArgumentException invalid) {
                throw invalid("Unknown task status");
            }
        }
        context.cancellation().check();
        ExecutionStatus filter = status;
        // Filter authority before sorting, counting or pagination so other scopes never affect results.
        List<ExecutionSnapshot> matches = repository.list().stream()
                .filter(snapshot -> visible(context, snapshot))
                .filter(snapshot -> filter == null || snapshot.status == filter)
                .sorted(
                        Comparator.comparing((ExecutionSnapshot snapshot) -> snapshot.createdAt).reversed()
                                .thenComparing(snapshot -> snapshot.executionId))
                .skip(offset).limit(limit + 1L).toList();
        context.cancellation().check();
        var page = new ArrayList<Map<String, Object>>();
        int size = 512; // Reserve room for the response envelope, including pagination.
        for (ExecutionSnapshot snapshot : matches.stream().limit(limit).toList()) {
            Map<String, Object> entry = summary(snapshot);
            int encoded = encodedLength(entry) + 1;
            if (size + encoded > MAX_RESPONSE_CHARS) {
                break;
            }
            page.add(entry);
            size += encoded;
        }
        boolean more = matches.size() > page.size();
        var result = result("ok");
        result.put("tasks", List.copyOf(page));
        result.put("offset", offset);
        result.put("has_more", more);
        result.put("next_offset", more && offset + page.size() <= 1_000_000 ? offset + page.size() : -1);
        result.put("pagination_limit_reached", more && offset + page.size() > 1_000_000);
        return result;
    }

    public Map<String, Object> get(ToolRegistry.Context context, Map<String, Object> input) {
        fields(input, Set.of("execution_id", "field", "offset", "limit", "expected_revision"));
        String id = text(input, "execution_id", 128);
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")) {
            throw invalid("Invalid execution ID");
        }
        String field = input.containsKey("field") ? text(input, "field", 16) : "answer";
        if (!Set.of("request", "goal", "answer").contains(field)) {
            throw invalid("Unsupported task field");
        }
        int offset = (int) number(input, "offset", 0, 0, Integer.MAX_VALUE);
        int limit = (int) number(input, "limit", MAX_CHUNK, 1, MAX_CHUNK);
        long expected = number(input, "expected_revision", -1, 1, Long.MAX_VALUE);
        if (offset > 0 && expected == -1) {
            throw invalid("Subsequent chunks require expected_revision");
        }
        context.cancellation().check();
        ExecutionSnapshot snapshot;
        try {
            snapshot = repository.get(id);
        } catch (NotFoundException missing) {
            return result("not_found");
        }
        if (!visible(context, snapshot)) {
            return result("not_found");
        }
        if (expected != -1 && expected != snapshot.revision) {
            var conflict = result("revision_conflict");
            conflict.put("current_revision", snapshot.revision);
            return conflict;
        }
        String contents = value(switch (field) {
            case "request" -> snapshot.originalRequest;
            case "goal" -> snapshot.currentGoal;
            default -> snapshot.finalAnswer;
        });
        // Offsets count UTF-16 code units, as in execution artifact reads. Keep surrogate pairs intact.
        int start = Math.min(offset, contents.length());
        if (start > 0 && start < contents.length() && Character.isLowSurrogate(contents.charAt(start))
                && Character.isHighSurrogate(contents.charAt(start - 1))) {
            start--;
        }
        int end = start + Math.min(limit, contents.length() - start);
        if (end > start && end < contents.length() && Character.isHighSurrogate(contents.charAt(end - 1))
                && Character.isLowSurrogate(contents.charAt(end))) {
            if (end - start == 1) {
                end++;
            } else {
                end--;
            }
        }
        var result = result("found");
        result.put("task", summary(snapshot));
        result.put("field", field);
        result.put("offset", start);
        result.put("total_chars", contents.length());
        while (true) {
            result.put("content_chunk", contents.substring(start, end));
            result.put("complete", end == contents.length());
            result.put("next_offset", end < contents.length() ? end : -1);
            if (encodedLength(result) <= MAX_RESPONSE_CHARS) {
                return result;
            }
            end = start + Math.max(1, (end - start) * 3 / 4);
            if (end < contents.length() && Character.isHighSurrogate(contents.charAt(end - 1))
                    && Character.isLowSurrogate(contents.charAt(end))) {
                end--;
            }
        }
    }

    private static boolean visible(ToolRegistry.Context context, ExecutionSnapshot snapshot) {
        return context.scope().sameOwner(snapshot.scope)
                && !context.executionId().equals(snapshot.executionId);
    }

    private static Map<String, Object> summary(ExecutionSnapshot snapshot) {
        String request = value(snapshot.originalRequest), answer = value(snapshot.finalAnswer);
        String requestPreview = preview(request, 200), answerPreview = preview(answer, 400);
        var result = new LinkedHashMap<String, Object>();
        result.put("execution_id", snapshot.executionId);
        result.put("status", snapshot.status.name());
        result.put("completion_basis", "model_response");
        result.put("revision", snapshot.revision);
        result.put("created_at", snapshot.createdAt.toString());
        result.put("updated_at", snapshot.updatedAt.toString());
        result.put("request_preview", requestPreview);
        result.put("answer_preview", answerPreview);
        result.put("request_truncated", requestPreview.length() < request.length());
        result.put("answer_truncated", answerPreview.length() < answer.length());
        return result;
    }

    private static String preview(String text, int limit) {
        int end = Math.min(limit, text.length());
        if (end > 0 && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))
                && Character.isLowSurrogate(text.charAt(end))) {
            end--;
        }
        return text.substring(0, end);
    }

    private static int encodedLength(Object value) {
        try {
            return JSON.writeValueAsString(value).length();
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot encode task history", failure);
        }
    }

    private static String value(String text) {
        return text == null ? "" : text;
    }

    private static LinkedHashMap<String, Object> result(String status) {
        var result = new LinkedHashMap<String, Object>();
        result.put("status", status);
        result.put("content_trust", "untrusted_data");
        return result;
    }

    private static void fields(Map<String, Object> input, Set<String> allowed) {
        if (input == null || !allowed.containsAll(input.keySet())) {
            throw invalid("Unsupported task history parameter");
        }
    }

    private static String text(Map<String, Object> input, String key, int max) {
        if (!(input.get(key) instanceof String value) || value.isBlank() || value.length() > max) {
            throw invalid(key + " must be a nonblank string of at most " + max + " characters");
        }
        return value;
    }

    private static long number(Map<String, Object> input, String key, long fallback, long min, long max) {
        if (!input.containsKey(key)) {
            return fallback;
        }
        if (!(input.get(key) instanceof Number value)) {
            throw invalid(key + " must be an integer");
        }
        try {
            long result = new BigDecimal(value.toString()).longValueExact();
            if (result < min || result > max) {
                throw invalid(key + " is out of range");
            }
            return result;
        } catch (NumberFormatException | ArithmeticException failure) {
            throw invalid(key + " must be an integer");
        }
    }

    private static InvalidRequestException invalid(String message) {
        return new InvalidRequestException(message);
    }

    public static final class InvalidRequestException extends IllegalArgumentException {

        public InvalidRequestException(String message) {
            super(message);
        }
    }
}
