package net.velocity.inference.claude;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Removes the JSON Schema keywords that Anthropic's structured outputs reject before an output schema goes on
 * the wire. The Messages API answers 400 for size, range and pattern constraints in
 * {@code output_config.format.schema}, and the Java SDK sends the schema verbatim, so the adapter strips them
 * the way the Python and TypeScript SDKs do. The request keeps its original schema: callers validate the
 * parsed response against it, which is where the stripped limits are still enforced.
 */
final class ClaudeOutputSchema {

    /** Constraint keywords the API rejects. */
    private static final Set<String> UNSUPPORTED = Set.of(
            "minLength",
            "maxLength",
            "pattern",
            "minimum",
            "maximum",
            "exclusiveMinimum",
            "exclusiveMaximum",
            "multipleOf",
            "minItems",
            "maxItems",
            "uniqueItems");
    /** The string formats the API accepts; any other {@code format} value is dropped. */
    private static final Set<String> FORMATS = Set.of(
            "date-time",
            "time",
            "date",
            "duration",
            "email",
            "hostname",
            "uri",
            "uuid",
            "ipv4",
            "ipv6");
    /** Keywords whose value is a map of named subschemas. */
    private static final Set<String> NAMED_SUBSCHEMAS = Set.of("properties", "$defs", "definitions");
    /** Keywords whose value is one subschema or a list of subschemas. */
    private static final Set<String> SUBSCHEMAS = Set.of(
            "items",
            "prefixItems",
            "additionalProperties",
            "anyOf",
            "allOf",
            "oneOf",
            "not");

    private ClaudeOutputSchema() {
    }

    /**
     * A copy of {@code schema} without the rejected keywords, walking nested properties, items, additional
     * properties, composition lists and definitions. Everything else, including {@code type},
     * {@code required}, {@code enum}, {@code const}, descriptions and {@code additionalProperties: false}, is
     * kept as given.
     */
    static Map<String, Object> sanitize(Map<?, ?> schema) {
        var result = new LinkedHashMap<String, Object>();
        schema.forEach((name, value) -> {
            String key = String.valueOf(name);
            if (UNSUPPORTED.contains(key) || "format".equals(key) && !FORMATS.contains(value)) {
                return;
            }
            if (NAMED_SUBSCHEMAS.contains(key) && value instanceof Map<?, ?> named) {
                var copies = new LinkedHashMap<String, Object>();
                named.forEach((child, subschema) -> copies.put(String.valueOf(child), subschema(subschema)));
                result.put(key, Collections.unmodifiableMap(copies));
            } else if (SUBSCHEMAS.contains(key)) {
                result.put(key, subschema(value));
            } else {
                result.put(key, value);
            }
        });
        return Collections.unmodifiableMap(result);
    }

    private static Object subschema(Object value) {
        if (value instanceof Map<?, ?> schema) {
            return sanitize(schema);
        }
        if (value instanceof List<?> schemas) {
            var copies = new ArrayList<Object>(schemas.size());
            schemas.forEach(item -> copies.add(subschema(item)));
            return Collections.unmodifiableList(copies);
        }
        return value;
    }
}
