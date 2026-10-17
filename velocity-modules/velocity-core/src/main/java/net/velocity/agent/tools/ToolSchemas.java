package net.velocity.agent.tools;

import java.util.List;
import java.util.Map;

/** Shared construction of the explicit schema dialect enforced by the gateway. */
public final class ToolSchemas {

    private ToolSchemas() {
    }

    /** Explicit Slack destination shape; domain services still decide destination authority. */
    public static Map<String, Object> destinationSchema() {
        return object(
                Map.of(
                        "transport",
                        enumeration("slack"),
                        "channel_id",
                        string(128),
                        "thread_ts",
                        string(64)),
                List.of("transport", "channel_id"));
    }

    public static Map<String, Object> object(Map<String, Object> fields, List<String> required) {
        return Map.of(
                "type",
                "object",
                "properties",
                Map.copyOf(fields),
                "required",
                List.copyOf(required),
                "additionalProperties",
                false);
    }

    public static Map<String, Object> object(Map<String, Object> fields, String... required) {
        return object(fields, List.of(required));
    }

    public static Map<String, Object> string(int maximum) {
        return Map.of("type", "string", "minLength", 1, "maxLength", maximum);
    }

    public static Map<String, Object> text(int maximum) {
        return Map.of("type", "string", "maxLength", maximum);
    }

    public static Map<String, Object> integer(long minimum, long maximum) {
        return Map.of("type", "integer", "minimum", minimum, "maximum", maximum);
    }

    public static Map<String, Object> bool() {
        return Map.of("type", "boolean");
    }

    public static Map<String, Object> array(Map<String, Object> item, int maximum) {
        return Map.of("type", "array", "items", item, "maxItems", maximum);
    }

    public static Map<String, Object> enumeration(String... values) {
        return Map.of("type", "string", "enum", List.of(values));
    }
}
