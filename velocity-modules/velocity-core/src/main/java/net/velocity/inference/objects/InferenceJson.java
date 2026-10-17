package net.velocity.inference.objects;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Copies JSON data without retaining mutable collections or provider SDK objects. */
final class InferenceJson {

    private InferenceJson() {
    }

    static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    static <T> List<T> content(List<T> values, String name) {
        if (values == null || values.isEmpty() || values.stream().anyMatch(value -> value == null)) {
            throw new IllegalArgumentException(name + " must not be empty or contain null");
        }
        return List.copyOf(values);
    }

    static Map<String, Object> copy(Map<String, ?> values) {
        if (values == null) {
            return Map.of();
        }
        var result = new LinkedHashMap<String, Object>();
        values.forEach((key, value) -> {
            if (key == null) {
                throw new IllegalArgumentException("JSON keys must not be null");
            }
            result.put(key, copyValue(value));
        });
        return Collections.unmodifiableMap(result);
    }

    private static Object copyValue(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof BigInteger || value instanceof BigDecimal) {
            return value;
        }
        if (value instanceof Double doubleValue && Double.isFinite(doubleValue)
                || value instanceof Float floatValue && Float.isFinite(floatValue)) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.forEach((key, item) -> {
                if (!(key instanceof String text)) {
                    throw new IllegalArgumentException("JSON keys must be strings");
                }
                result.put(text, copyValue(item));
            });
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof List<?> list) {
            var result = new ArrayList<>();
            list.forEach(item -> result.add(copyValue(item)));
            return Collections.unmodifiableList(result);
        }
        throw new IllegalArgumentException("Expected JSON data, got " + value.getClass().getName());
    }
}
