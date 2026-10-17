package net.velocity.agent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import net.velocity.agent.state.TaskOutcome;

/** Reads only the explicit final-response protocol. Ordinary prose never implies task success. */
final class TaskOutcomeReport {

    private static final String OPEN = "```velocity-task-outcome\n";
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    record Parsed(String answer, TaskOutcome outcome) {}

    static Parsed parse(String response, long generation, Instant reportedAt) {
        var unchanged = new Parsed(response, null);
        if (response == null) {
            return unchanged;
        }
        String normalized = response.replace("\r\n", "\n").stripTrailing();
        int start = normalized.lastIndexOf(OPEN);
        if (start < 0 || start > 0 && normalized.charAt(start - 1) != '\n'
                || !normalized.endsWith("\n```") || normalized.indexOf(OPEN) != start) {
            return unchanged;
        }
        String answer = normalized.substring(0, start).stripTrailing();
        String encoded = normalized.substring(start + OPEN.length(), normalized.length() - 4);
        if (answer.isBlank() || encoded.length() > 80_000) {
            return unchanged;
        }
        try {
            JsonNode node = JSON.readTree(encoded);
            if (node == null || !node.isObject()) {
                return unchanged;
            }
            var fields = new HashSet<String>();
            node.fieldNames().forEachRemaining(fields::add);
            if (!fields.equals(Set.of("status", "criteria", "evidence", "limitations"))
                    || !node.get("status").isTextual()) {
                return unchanged;
            }
            var outcome = new TaskOutcome(
                    TaskOutcome.Status.valueOf(node.get("status").textValue()),
                    strings(node.get("criteria")),
                    strings(node.get("evidence")),
                    strings(node.get("limitations")),
                    "model_report",
                    generation,
                    reportedAt);
            return new Parsed(answer, outcome);
        } catch (Exception invalid) {
            // A missing or malformed declaration is not a failed execution or a verified success.
            return unchanged;
        }
    }

    private static List<String> strings(JsonNode node) {
        if (node == null || !node.isArray() || node.size() > 12) {
            throw new IllegalArgumentException("Invalid task outcome details");
        }
        var values = new ArrayList<String>();
        for (JsonNode value : node) {
            if (!value.isTextual()) {
                throw new IllegalArgumentException("Invalid task outcome detail");
            }
            values.add(value.textValue());
        }
        return values;
    }
}
