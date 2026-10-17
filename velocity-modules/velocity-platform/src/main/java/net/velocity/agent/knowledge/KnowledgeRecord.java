package net.velocity.agent.knowledge;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A committed JSON record and the discovery metadata committed alongside it. */
public record KnowledgeRecord(
        String id,
        String ownerAccountId,
        String dataset,
        String key,
        String title,
        String description,
        List<String> tags,
        Map<String, Object> content,
        long revision,
        Instant createdAt,
        Instant updatedAt,
        String createdByExecutionId,
        String updatedByExecutionId,
        List<String> observedFields) {

    public KnowledgeRecord {
        tags = List.copyOf(tags);
        content = immutableMap(content);
        observedFields = List.copyOf(observedFields);
    }

    private static Map<String, Object> immutableMap(Map<String, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, immutable(value)));
        // JSON permits null values, which Map.copyOf deliberately rejects.
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutable(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, child) -> copy.put((String) key, immutable(child)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(child -> copy.add(immutable(child)));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}
