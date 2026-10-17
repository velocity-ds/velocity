package net.velocity.agent.http;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.velocity.agent.JsonSchema;

/** Fixed external schemas reject unknown fields before service dispatch; request bodies carry no authority. */
public final class OperatorDataRequests {

    private OperatorDataRequests() {
    }

    public static Map<String, Object> read(String body, Map<String, Object> schema) {
        try {
            var input = StrictRequests.object(body, StrictRequests.Numbers.PRECISE_FLOATS);
            JsonSchema.validate(schema, input);
            normalizeIntegers(schema, input);
            return input;
        } catch (IOException | ArithmeticException invalid) {
            throw new IllegalArgumentException("Invalid JSON request", invalid);
        }
    }

    /** Preserve the former DTO integer encoding without changing free-form content numbers. */
    @SuppressWarnings("unchecked")
    private static Object normalizeIntegers(Map<String, Object> schema, Object value) {
        if ("integer".equals(schema.get("type"))) {
            return new BigDecimal(value.toString()).longValueExact();
        }
        if (value instanceof Map<?, ?> && schema.get("properties") instanceof Map<?, ?> fields) {
            var object = (Map<String, Object>) value;
            for (var field : fields.entrySet()) {
                String key = (String) field.getKey();
                if (object.containsKey(key)) {
                    object.put(
                            key,
                            normalizeIntegers((Map<String, Object>) field.getValue(), object.get(key)));
                }
            }
        } else if (value instanceof List<?> && schema.get("items") instanceof Map<?, ?> items) {
            ((List<Object>) value).replaceAll(item -> normalizeIntegers((Map<String, Object>) items, item));
        }
        return value;
    }

    public static Map<String, Object> read(
            String body,
            Map<String, String> parameters,
            Map<String, Object> schema) {
        noParameters(parameters);
        return read(body, schema);
    }

    public static Long revision(Map<String, Object> input) {
        return input.get("expected_revision") instanceof Number value ? value.longValue() : null;
    }

    /** Narrow a trusted schema for a fixed local endpoint; never add authority-bearing fields. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> fields(Map<String, Object> schema, Set<String> allowed) {
        var properties = new LinkedHashMap<>((Map<String, Object>) schema.get("properties"));
        properties.keySet().retainAll(allowed);
        var required = ((List<String>) schema.get("required")).stream().filter(allowed::contains).toList();
        return Map.of(
                "type",
                "object",
                "properties",
                properties,
                "required",
                required,
                "additionalProperties",
                false);
    }

    public static void noParameters(Map<String, String> parameters) {
        StrictRequests.noParameters(parameters, "This route accepts no query parameters");
    }

    public static void noBody(String body, Map<String, String> parameters) {
        noParameters(parameters);
        StrictRequests.empty(
                body,
                StrictRequests.EmptyBody.UNICODE_WHITESPACE,
                "This route accepts no request body");
    }
}
