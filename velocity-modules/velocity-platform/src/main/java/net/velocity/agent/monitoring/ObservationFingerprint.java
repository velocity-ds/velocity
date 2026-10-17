package net.velocity.agent.monitoring;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/** Stable identity and content hashes never include generated summaries, relevance prose or timestamps. */
public final class ObservationFingerprint {

    private ObservationFingerprint() {
    }

    public static String fingerprint(Map<String, JsonNode> facts) {
        if (facts == null || facts.isEmpty()) {
            throw new IllegalArgumentException("Observation facts are required");
        }
        var root = JsonNodeFactory.instance.objectNode();
        facts.forEach(root::set);
        return hash(normalize(root).toString());
    }

    public static String fingerprint(String sourceId, String itemId, Map<String, JsonNode> facts) {
        return hash(itemKey(sourceId, itemId) + ":" + fingerprint(facts));
    }

    public static String itemKey(String sourceId, String itemId) {
        return hash(tuple(sourceId, itemId));
    }

    public static String versionId(
            String jobId,
            String runId,
            String sourceId,
            String itemId,
            String fingerprint) {
        return "obs-" + hash(tuple(jobId, runId, sourceId, itemId, fingerprint));
    }

    private static String tuple(String... values) {
        var array = JsonNodeFactory.instance.arrayNode();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Stable identity is required");
            }
            array.add(normalizeText(value));
        }
        return array.toString();
    }

    private static JsonNode normalize(JsonNode value) {
        if (value == null || value.isNull()) {
            return JsonNodeFactory.instance.nullNode();
        }
        if (value.isObject()) {
            var sorted = new TreeMap<String, JsonNode>();
            value.properties().forEach(entry -> {
                if (sorted.put(normalizeText(entry.getKey()), normalize(entry.getValue())) != null) {
                    throw new IllegalArgumentException("Fact names collide after normalization");
                }
            });
            var result = JsonNodeFactory.instance.objectNode();
            sorted.forEach(result::set);
            return result;
        }
        if (value.isArray()) {
            var result = JsonNodeFactory.instance.arrayNode();
            value.forEach(item -> result.add(normalize(item)));
            return result;
        }
        if (value.isTextual()) {
            return JsonNodeFactory.instance.textNode(normalizeText(value.textValue()));
        }
        if (value.isNumber()) {
            return JsonNodeFactory.instance.numberNode(value.decimalValue().stripTrailingZeros());
        }
        if (value.isBoolean()) {
            return value;
        }
        throw new IllegalArgumentException("Unsupported fact value");
    }

    private static String normalizeText(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC).strip().replaceAll("(?U)\\s+", " ");
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
