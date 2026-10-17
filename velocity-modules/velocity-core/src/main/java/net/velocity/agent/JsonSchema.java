package net.velocity.agent;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

/** Deliberately bounded schema dialect. Unsupported constraints fail registration, never silently pass. */
public final class JsonSchema {

    private static final Set<String> KEYS = Set.of(
            "type",
            "properties",
            "required",
            "additionalProperties",
            "items",
            "enum",
            "minimum",
            "maximum",
            "minLength",
            "maxLength",
            "minItems",
            "maxItems",
            "description",
            "title");

    private JsonSchema() {
    }

    public static void check(Map<String, Object> schema) {
        check(schema, 0);
    }

    private static void check(Map<String, Object> schema, int depth) {
        if (schema == null || depth > 64) {
            throw new IllegalArgumentException("Invalid or deeply nested tool schema");
        }
        if (!KEYS.containsAll(schema.keySet())) {
            throw new IllegalArgumentException("Unsupported tool schema keyword");
        }
        if (!(schema.get("type") instanceof String type) || !Set
                .of("object", "array", "string", "boolean", "integer", "number", "null").contains(type)) {
            throw new IllegalArgumentException("Tool schema requires a supported type");
        }
        for (String field : List.of("description", "title")) {
            if (schema.containsKey(field) && !(schema.get(field) instanceof String)) {
                throw new IllegalArgumentException("Tool schema " + field + " must be a string");
            }
        }
        if (schema.containsKey("properties")) {
            map(schema.get("properties")).values().forEach(v -> check(map(v), depth + 1));
        }
        if (schema.containsKey("items")) {
            check(map(schema.get("items")), depth + 1);
        }
        if (schema.containsKey("additionalProperties")) {
            Object additional = schema.get("additionalProperties");
            if (!(additional instanceof Boolean)) {
                check(map(additional), depth + 1);
            }
        }
        if (schema.containsKey("required")) {
            if (!(schema.get("required") instanceof List<?> required)
                    || required.stream().anyMatch(value -> !(value instanceof String))
                    || new HashSet<>(required).size() != required.size()) {
                throw new IllegalArgumentException(
                        "Tool schema required must contain distinct property names");
            }
        }
        for (String field : List.of("minimum", "maximum", "minLength", "maxLength", "minItems", "maxItems")) {
            if (!schema.containsKey(field)) {
                continue;
            }
            BigDecimal number = number(schema.get(field));
            if (!Set.of("minimum", "maximum").contains(field)
                    && (number.signum() < 0 || number.stripTrailingZeros().scale() > 0)) {
                throw new IllegalArgumentException("Tool schema " + field + " must be a nonnegative integer");
            }
        }
        for (var pair : List.of(
                List.of("minimum", "maximum"),
                List.of("minLength", "maxLength"),
                List.of("minItems", "maxItems"))) {
            if (schema.containsKey(pair.get(0)) && schema.containsKey(pair.get(1))
                    && number(schema.get(pair.get(0))).compareTo(number(schema.get(pair.get(1)))) > 0) {
                throw new IllegalArgumentException("Tool schema minimum exceeds maximum");
            }
        }
        if (schema.containsKey("enum")) {
            if (!(schema.get("enum") instanceof List<?> values) || values.isEmpty()) {
                throw new IllegalArgumentException("Tool schema enum must be a nonempty list");
            }
            for (int index = 0; index < values.size(); index++) {
                json(values.get(index), depth + 1);
                for (int prior = 0; prior < index; prior++) {
                    if (equivalent(values.get(index), values.get(prior))) {
                        throw new IllegalArgumentException("Tool schema enum contains duplicate values");
                    }
                }
            }
        }
    }

    public static void validate(Map<String, Object> schema, Object value) {
        check(schema);
        json(value, 0);
        validateValue(schema, value);
    }

    private static void validateValue(Map<String, Object> schema, Object value) {
        String type = (String) schema.get("type");
        boolean valid = switch (type) {
            case "object" -> value instanceof Map<?, ?>;
            case "array" -> value instanceof List<?>;
            case "string" -> value instanceof String;
            case "boolean" -> value instanceof Boolean;
            case "number" -> value instanceof Number;
            case "integer" -> value instanceof Number && number(value).stripTrailingZeros().scale() <= 0;
            case "null" -> value == null;
            default -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException("Tool value has incorrect type");
        }
        if (schema.get("enum") instanceof List<?> values
                && values.stream().noneMatch(candidate -> equivalent(candidate, value))) {
            throw new IllegalArgumentException("Tool value outside enum");
        }
        if (value instanceof Map<?, ?> object) {
            Map<String, Object> properties = schema.containsKey("properties")
                    ? map(schema.get("properties"))
                    : Map.of();
            if (schema.get("required") instanceof List<?> required
                    && !object.keySet().containsAll(required)) {
                throw new IllegalArgumentException("Missing tool property");
            }
            for (var entry : object.entrySet()) {
                Object child = properties.get(entry.getKey());
                if (child != null) {
                    validateValue(map(child), entry.getValue());
                } else if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
                    throw new IllegalArgumentException("Unexpected tool property");
                } else if (schema.get("additionalProperties") instanceof Map<?, ?> additional) {
                    validateValue(map(additional), entry.getValue());
                }
            }
        }
        if (value instanceof List<?> values) {
            bound(schema, "minItems", "maxItems", BigDecimal.valueOf(values.size()));
            if (schema.containsKey("items")) {
                values.forEach(v -> validateValue(map(schema.get("items")), v));
            }
        }
        if (value instanceof String text) {
            bound(
                    schema,
                    "minLength",
                    "maxLength",
                    BigDecimal.valueOf(text.codePointCount(0, text.length())));
        }
        if (value instanceof Number) {
            bound(schema, "minimum", "maximum", number(value));
        }
    }

    private static void bound(Map<String, Object> schema, String min, String max, BigDecimal value) {
        if (schema.containsKey(min) && value.compareTo(number(schema.get(min))) < 0
                || schema.containsKey(max) && value.compareTo(number(schema.get(max))) > 0) {
            throw new IllegalArgumentException("Tool value outside bounds");
        }
    }

    private static BigDecimal number(Object value) {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long
                || value instanceof BigInteger || value instanceof BigDecimal || value instanceof Float
                || value instanceof Double)) {
            throw new IllegalArgumentException("Expected a JSON number");
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Expected a finite JSON number");
        }
    }

    private static void json(Object value, int depth) {
        if (depth > 64) {
            throw new IllegalArgumentException("Tool JSON exceeds maximum nesting");
        }
        if (value == null || value instanceof String || value instanceof Boolean) {
            return;
        }
        if (value instanceof Number) {
            number(value);
            return;
        }
        if (value instanceof List<?> list) {
            list.forEach(item -> json(item, depth + 1));
            return;
        }
        if (value instanceof Map<?, ?> object) {
            for (var entry : object.entrySet()) {
                if (!(entry.getKey() instanceof String)) {
                    throw new IllegalArgumentException("JSON object keys must be strings");
                }
                json(entry.getValue(), depth + 1);
            }
            return;
        }
        throw new IllegalArgumentException("Tool value is not JSON data");
    }

    private static boolean equivalent(Object left, Object right) {
        if (left instanceof Number && right instanceof Number) {
            return number(left).compareTo(number(right)) == 0;
        }
        if (left instanceof List<?> a && right instanceof List<?> b) {
            if (a.size() != b.size()) {
                return false;
            }
            for (int index = 0; index < a.size(); index++) {
                if (!equivalent(a.get(index), b.get(index))) {
                    return false;
                }
            }
            return true;
        }
        if (left instanceof Map<?, ?> a && right instanceof Map<?, ?> b) {
            return a.keySet().equals(b.keySet())
                    && a.keySet().stream().allMatch(key -> equivalent(a.get(key), b.get(key)));
        }
        return Objects.equals(left, right);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> object)
                || object.keySet().stream().anyMatch(key -> !(key instanceof String))) {
            throw new IllegalArgumentException("Invalid schema object");
        }
        return (Map<String, Object>) value;
    }
}
