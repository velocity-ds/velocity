package net.velocity.agent.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import net.velocity.agent.state.CallApproval;

/**
 * Plain-language descriptions of pending knowledge writes, rendered by trusted Java from the validated call
 * arguments and the current record so the requester confirms exactly what will be stored, changed or
 * removed. Output is bounded to {@link CallApproval#MAX_SUMMARY_CHARS}.
 */
final class KnowledgePreview {

    private static final int MAX_CHARS = CallApproval.MAX_SUMMARY_CHARS - 100;
    private static final int MAX_VALUE_CHARS = 200;
    private static final int MAX_LINES = 60;

    private KnowledgePreview() {
    }

    static String created(KnowledgeService.Draft draft) {
        var text = new StringBuilder();
        text.append("Save a new record to dataset \"").append(draft.dataset()).append("\" (key ")
                .append(draft.key()).append(").\n");
        text.append("Title: ").append(value(draft.title())).append('\n');
        if (!draft.description().isBlank()) {
            text.append("Description: ").append(value(draft.description())).append('\n');
        }
        if (!draft.tags().isEmpty()) {
            text.append("Tags: ").append(String.join(", ", draft.tags())).append('\n');
        }
        text.append("Fields:\n");
        var fields = flatten(draft.content());
        if (fields.isEmpty()) {
            text.append("• (no fields)\n");
        }
        return bounded(
                text,
                fields.entrySet().stream().map(entry -> "• " + entry.getKey() + ": " + entry.getValue())
                        .toList());
    }

    static String replaced(KnowledgeRecord existing, KnowledgeService.Draft draft, String verb) {
        var text = new StringBuilder();
        text.append(verb).append(" record \"").append(value(existing.title())).append("\" in dataset \"")
                .append(existing.dataset()).append("\" (key ").append(existing.key()).append(", revision ")
                .append(existing.revision()).append(").\n");
        if (!existing.title().equals(draft.title())) {
            text.append("Title: ").append(value(existing.title())).append(" → ").append(value(draft.title()))
                    .append('\n');
        }
        if (!existing.description().equals(draft.description())) {
            text.append("Description: ").append(value(existing.description())).append(" → ")
                    .append(value(draft.description())).append('\n');
        }
        if (!existing.tags().equals(draft.tags())) {
            text.append("Tags: ").append(String.join(", ", existing.tags())).append(" → ")
                    .append(String.join(", ", draft.tags())).append('\n');
        }
        var before = flatten(existing.content());
        var after = flatten(draft.content());
        var lines = new ArrayList<String>();
        int unchanged = 0;
        var paths = new LinkedHashSet<>(before.keySet());
        paths.addAll(after.keySet());
        for (String path : paths) {
            String old = before.get(path);
            String current = after.get(path);
            if (old == null) {
                lines.add("• " + path + ": " + current + " (new)");
            } else if (current == null) {
                lines.add("• " + path + ": " + old + " (removed)");
            } else if (!old.equals(current)) {
                lines.add("• " + path + ": " + old + " → " + current);
            } else {
                unchanged++;
            }
        }
        if (lines.isEmpty()) {
            text.append("Fields: no changes");
            if (unchanged > 0) {
                text.append(" (").append(unchanged).append(unchanged == 1 ? " field" : " fields")
                        .append(" kept as is)");
            }
            text.append('\n');
            return bounded(text, List.of());
        }
        text.append("Field changes:\n");
        if (unchanged > 0) {
            lines.add(unchanged + (unchanged == 1 ? " other field stays" : " other fields stay") + " as is.");
        }
        return bounded(text, lines);
    }

    static String deleted(KnowledgeRecord record) {
        var text = new StringBuilder();
        text.append("Delete record \"").append(value(record.title())).append("\" from dataset \"")
                .append(record.dataset()).append("\" (key ").append(record.key()).append(", revision ")
                .append(record.revision()).append("). This cannot be undone.\n");
        if (!record.description().isBlank()) {
            text.append("Description: ").append(value(record.description())).append('\n');
        }
        if (!record.tags().isEmpty()) {
            text.append("Tags: ").append(String.join(", ", record.tags())).append('\n');
        }
        var fields = flatten(record.content());
        text.append(fields.isEmpty() ? "It holds no fields.\n" : "It holds:\n");
        return bounded(
                text,
                fields.entrySet().stream().map(entry -> "• " + entry.getKey() + ": " + entry.getValue())
                        .toList());
    }

    static String missing(String action, String dataset, String key, String id) {
        return action + " record "
                + (id != null ? "with id " + id : "in dataset \"" + dataset + "\" (key " + key + ")")
                + ": no such record exists, so the call will report not_found and change nothing.";
    }

    static String conflict(String action, KnowledgeRecord current, long expected) {
        return action + " record \"" + value(current.title()) + "\" in dataset \"" + current.dataset()
                + "\" (key "
                + current.key() + "): the call expects revision " + expected
                + " but the record is at revision "
                + current.revision() + ", so it will report a conflict and change nothing.";
    }

    static String invalid(String action, String message) {
        return action + " record: the call is invalid and will be rejected without changing anything ("
                + message + ").";
    }

    /** Leaf paths in document order, such as {@code events[1].amount}, each with a bounded rendered value. */
    static LinkedHashMap<String, String> flatten(Map<String, Object> content) {
        var fields = new LinkedHashMap<String, String>();
        flatten("", content, fields);
        return fields;
    }

    private static void flatten(String prefix, Object value, Map<String, String> fields) {
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                if (!prefix.isEmpty()) {
                    fields.put(prefix, "{}");
                }
                return;
            }
            map.forEach(
                    (key, child) -> flatten(
                            prefix.isEmpty() ? String.valueOf(key) : prefix + "." + key,
                            child,
                            fields));
            return;
        }
        if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                fields.put(prefix, "[]");
                return;
            }
            if (list.stream().noneMatch(item -> item instanceof Map<?, ?> || item instanceof List<?>)) {
                var rendered = new ArrayList<String>();
                list.forEach(item -> rendered.add(scalar(item)));
                fields.put(prefix, truncate(String.join(", ", rendered)));
                return;
            }
            for (int index = 0; index < list.size(); index++) {
                flatten(prefix + "[" + index + "]", list.get(index), fields);
            }
            return;
        }
        fields.put(prefix, scalar(value));
    }

    private static String scalar(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return value(text);
        }
        return truncate(String.valueOf(value));
    }

    private static String value(String text) {
        return truncate(text.replace('\r', ' ').replace('\n', ' ').strip());
    }

    private static String truncate(String text) {
        return text.length() <= MAX_VALUE_CHARS ? text : text.substring(0, MAX_VALUE_CHARS - 1) + "…";
    }

    private static String bounded(StringBuilder heading, List<String> lines) {
        var text = new StringBuilder(heading);
        int shown = 0;
        for (String line : lines) {
            if (shown >= MAX_LINES || text.length() + line.length() + 1 > MAX_CHARS) {
                break;
            }
            text.append(line).append('\n');
            shown++;
        }
        if (shown < lines.size()) {
            int more = lines.size() - shown;
            text.append("… and ").append(more).append(more == 1 ? " more line." : " more lines.")
                    .append('\n');
        }
        return text.toString().stripTrailing();
    }
}
