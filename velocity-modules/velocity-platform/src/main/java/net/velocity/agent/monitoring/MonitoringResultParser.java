package net.velocity.agent.monitoring;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/** Strict bounded parsing checks structure and local references, not the truth of model findings. */
public final class MonitoringResultParser {

    private final MonitoringRecordCodec codec = new MonitoringRecordCodec();

    public MonitoringResult parse(String text, int maxObservations, int maxCandidates, int maxFactsChars) {
        if (maxObservations < 1 || maxCandidates < 1 || maxFactsChars < 1) {
            throw new IllegalArgumentException("Invalid monitoring result limits");
        }
        if (text == null || text.length() > 4_000_000) {
            throw new IllegalArgumentException("Monitoring result is missing or exceeds its size limit");
        }
        try {
            JsonNode root = codec.mapper.readTree(text);
            object(
                    root,
                    Set.of(
                            "outcome",
                            "summary",
                            "observations",
                            "updates",
                            "coverage",
                            "freshness",
                            "checkpoints",
                            "questions",
                            "limitations",
                            "completion_evidence"));
            requiredText(root, "outcome", 20, true);
            requiredText(root, "summary", 16000, false);
            requiredText(root, "freshness", 4000, false);
            array(root, "observations", maxObservations);
            array(root, "updates", maxCandidates);
            array(root, "coverage", 100);
            strings(root.get("questions"), 20, 2000);
            strings(root.get("limitations"), 30, 2000);
            if (root.has("completion_evidence")) {
                strings(root.get("completion_evidence"), 20, 2000);
            }
            JsonNode checkpoints = root.get("checkpoints");
            if (checkpoints == null || !checkpoints.isObject() || checkpoints.size() > 100) {
                throw new IllegalArgumentException("checkpoints must be an object with at most 100 entries");
            }
            checkpoints.properties().forEach(entry -> {
                bounded(entry.getKey(), 200, true);
                scalarText(entry.getValue(), 4000, true);
            });
            var keys = new HashSet<String>();
            var items = new HashSet<String>();
            for (JsonNode observation : root.get("observations")) {
                object(
                        observation,
                        Set.of(
                                "key",
                                "source_id",
                                "item_id",
                                "evidence",
                                "source_time",
                                "facts",
                                "prior_observation_id",
                                "relevance"));
                requiredText(observation, "key", 128, true);
                requiredText(observation, "source_id", 500, true);
                requiredText(observation, "item_id", 1000, true);
                requiredText(observation, "relevance", 4000, true);
                if (!keys.add(observation.get("key").textValue())) {
                    throw new IllegalArgumentException("Observation keys must be unique");
                }
                String item = ObservationFingerprint.itemKey(
                        observation.get("source_id").textValue(),
                        observation.get("item_id").textValue());
                if (!items.add(item)) {
                    throw new IllegalArgumentException("A run cannot contain duplicate source items");
                }
                strings(observation.get("evidence"), 20, 2000);
                if (observation.get("evidence").isEmpty()) {
                    throw new IllegalArgumentException("Every observation requires an evidence reference");
                }
                optionalText(observation, "source_time", 100);
                optionalText(observation, "prior_observation_id", 128);
                JsonNode facts = observation.get("facts");
                if (facts == null || !facts.isObject() || facts.isEmpty() || facts.size() > 100
                        || facts.toString().length() > maxFactsChars) {
                    throw new IllegalArgumentException("Observation facts must be a nonempty bounded object");
                }
                fact(facts, 0);
            }
            for (JsonNode update : root.get("updates")) {
                object(update, Set.of("observation_keys", "change", "relevance", "text"));
                strings(update.get("observation_keys"), maxObservations, 128);
                if (update.get("observation_keys").isEmpty()) {
                    throw new IllegalArgumentException("Candidate updates must reference observations");
                }
                var references = new HashSet<String>();
                for (JsonNode key : update.get("observation_keys")) {
                    if (!keys.contains(key.textValue()) || !references.add(key.textValue())) {
                        throw new IllegalArgumentException(
                                "Candidate update has an invalid or duplicate observation reference");
                    }
                }
                requiredText(update, "change", 30, true);
                requiredText(update, "relevance", 4000, true);
                optionalText(update, "text", 16000);
            }
            if (root.has("completion_evidence")) {
                var referenced = new HashSet<String>();
                for (JsonNode key : root.get("completion_evidence")) {
                    if (!keys.contains(key.textValue()) || !referenced.add(key.textValue())) {
                        throw new IllegalArgumentException(
                                "Completion evidence must reference unique observation keys");
                    }
                }
            }
            Set<String> covered = new HashSet<>();
            for (JsonNode coverage : root.get("coverage")) {
                object(coverage, Set.of("source_id", "complete", "detail"));
                requiredText(coverage, "source_id", 500, true);
                requiredText(coverage, "detail", 4000, false);
                if (!coverage.has("complete") || !coverage.get("complete").isBoolean()) {
                    throw new IllegalArgumentException("Coverage complete must be a boolean");
                }
                if (!covered.add(coverage.get("source_id").textValue())) {
                    throw new IllegalArgumentException("Coverage sources must be unique");
                }
            }
            for (JsonNode observation : root.get("observations")) {
                if (!covered.contains(observation.get("source_id").textValue())) {
                    throw new IllegalArgumentException("Every observation source requires a coverage entry");
                }
            }
            MonitoringResult parsed = codec.mapper.treeToValue(root, MonitoringResult.class);
            if (parsed.outcome() == MonitoringResult.Outcome.needs_input && parsed.questions().isEmpty()) {
                throw new IllegalArgumentException("needs_input requires an actionable question");
            }
            if (parsed.outcome() == MonitoringResult.Outcome.success
                    && parsed.coverage().stream().anyMatch(coverage -> !coverage.complete())) {
                throw new IllegalArgumentException(
                        "A successful result cannot claim incomplete source coverage");
            }
            if (parsed.outcome() == MonitoringResult.Outcome.failed && !parsed.checkpoints().isEmpty()) {
                throw new IllegalArgumentException("Failed checks cannot advance checkpoints");
            }
            for (String checkpoint : parsed.checkpoints().keySet()) {
                if (parsed.coverage().stream().noneMatch(
                        coverage -> coverage.sourceId().equals(checkpoint)
                                && coverage.complete())) {
                    throw new IllegalArgumentException(
                            "Checkpoint proposals require complete coverage for the source");
                }
            }
            return new MonitoringResult(
                    parsed.outcome(),
                    parsed.summary(),
                    List.copyOf(parsed.observations()),
                    List.copyOf(parsed.updates()),
                    List.copyOf(parsed.coverage()),
                    parsed.freshness(),
                    Map.copyOf(parsed.checkpoints()),
                    List.copyOf(parsed.questions()),
                    List.copyOf(parsed.limitations()),
                    parsed.completionEvidence() == null
                            ? List.of()
                            : List.copyOf(parsed.completionEvidence()));
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException("Invalid structured monitoring result", failure);
        }
    }

    private static void fact(JsonNode node, int depth) {
        if (depth > 6) {
            throw new IllegalArgumentException("Observation facts are too deeply nested");
        }
        if (node.isObject()) {
            if (node.size() > 100) {
                throw new IllegalArgumentException("Too many fact properties");
            }
            node.properties().forEach(entry -> {
                bounded(entry.getKey(), 128, true);
                if (entry.getKey().contains(".") || entry.getKey().startsWith("$")) {
                    throw new IllegalArgumentException(
                            "Fact keys cannot contain dots or start with dollar signs");
                }
                fact(entry.getValue(), depth + 1);
            });
        } else if (node.isArray()) {
            if (node.size() > 100) {
                throw new IllegalArgumentException("Fact arrays are too long");
            }
            node.forEach(value -> fact(value, depth + 1));
        } else if (node.isTextual()) {
            bounded(node.textValue(), 8000, false);
        } else if (!node.isNumber() && !node.isBoolean() && !node.isNull()) {
            throw new IllegalArgumentException("Unsupported fact value");
        }
    }

    private static void object(JsonNode value, Set<String> fields) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException("Expected a JSON object");
        }
        value.fieldNames().forEachRemaining(field -> {
            if (!fields.contains(field)) {
                throw new IllegalArgumentException("Unknown monitoring result field: " + field);
            }
        });
    }

    private static void array(JsonNode parent, String field, int max) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isArray() || value.size() > max) {
            throw new IllegalArgumentException(field + " must be a bounded array");
        }
    }

    private static void strings(JsonNode value, int max, int chars) {
        if (value == null || !value.isArray() || value.size() > max) {
            throw new IllegalArgumentException("Expected a bounded string array");
        }
        for (JsonNode item : value) {
            scalarText(item, chars, true);
        }
    }

    private static void requiredText(JsonNode parent, String key, int chars, boolean nonblank) {
        scalarText(parent.get(key), chars, nonblank);
    }

    private static void optionalText(JsonNode parent, String key, int chars) {
        JsonNode node = parent.get(key);
        if (node != null && !node.isNull()) {
            scalarText(node, chars, true);
        }
    }

    private static void scalarText(JsonNode value, int max, boolean nonblank) {
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("Expected a string");
        }
        bounded(value.textValue(), max, nonblank);
    }

    private static void bounded(String value, int max, boolean nonblank) {
        if (value.length() > max || nonblank && value.isBlank()) {
            throw new IllegalArgumentException("Monitoring result string is empty or exceeds its size limit");
        }
    }
}
