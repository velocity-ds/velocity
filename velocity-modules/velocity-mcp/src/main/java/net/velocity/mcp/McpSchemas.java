package net.velocity.mcp;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.velocity.agent.JsonSchema;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * Imports a server's tool metadata into the kernel's bounded schema dialect and permanent tool names.
 *
 * <p>The conversion is permissive, never stricter than the server: every keyword the dialect cannot express
 * ({@code anyOf}, {@code $ref}, {@code pattern}, {@code format}, defaults and so on) is dropped, and an argument
 * whose type cannot be determined at all is left out of {@code properties} (so any JSON value passes) and named
 * in the tool description instead. The server remains the authority on its own arguments; the imported schema
 * only lets the executor reject calls that are plainly malformed and shows the model a typed signature.
 */
final class McpSchemas {

    static final String PREFIX = "mcp_";

    static final int MAX_TOOL_NAME = 64;

    static final int MAX_DESCRIPTION_CHARS = 4_000;

    private static final int MAX_FIELD_TEXT_CHARS = 1_000;

    private static final int MAX_DEPTH = 32;

    private static final int MAX_PROPERTIES = 256;

    private static final int MAX_ENUM_VALUES = 256;

    private static final int MAX_UNTYPED_NAMED = 20;

    private static final Set<String> TYPES = Set
            .of("object", "array", "string", "boolean", "integer", "number", "null");

    private McpSchemas() {
    }

    /** The bounded input schema plus the argument paths it could not type. */
    record Imported(Map<String, Object> schema, List<String> untyped) {}

    static Imported importInput(Map<String, Object> raw) {
        if (raw == null) {
            return new Imported(Map.of("type", "object"), List.of());
        }
        var untyped = new ArrayList<String>();
        Map<String, Object> schema = convert(raw, 0, "", untyped);
        if (schema == null || !"object".equals(schema.get("type"))) {
            return new Imported(Map.of("type", "object"), List.of("(all arguments)"));
        }
        try {
            JsonSchema.check(schema);
        } catch (IllegalArgumentException inexpressible) {
            return new Imported(Map.of("type", "object"), List.of("(all arguments)"));
        }
        return new Imported(schema, List.copyOf(untyped));
    }

    /** The permanent registered name: the prefix, the configured server name and the sanitized tool name. */
    static String registeredName(String server, String tool) {
        String part = tool == null ? "" : tool.replaceAll("[^a-zA-Z0-9_-]", "_");
        if (part.isEmpty()) {
            part = "tool";
        }
        String prefix = PREFIX + server + "_";
        int room = MAX_TOOL_NAME - prefix.length();
        if (part.length() > room) {
            part = part.substring(0, room);
        }
        return prefix + part;
    }

    /** The model-visible description: operator prefix, the server's cleaned text, then any untyped arguments. */
    static String description(String server, McpSchema.Tool tool, List<String> untyped) {
        String text = tool.description();
        if (text == null || text.isBlank()) {
            text = tool.title();
        }
        if (text == null || text.isBlank()) {
            text = McpPrompts.NO_DESCRIPTION;
        }
        var description = new StringBuilder(McpPrompts.descriptionPrefix(server))
                .append(clean(text, MAX_DESCRIPTION_CHARS - 400));
        if (!untyped.isEmpty()) {
            var names = new ArrayList<String>();
            for (String name : untyped) {
                if (names.size() >= MAX_UNTYPED_NAMED) {
                    names.add("...");
                    break;
                }
                names.add(clean(name, 80));
            }
            description.append(McpPrompts.untypedArguments(String.join(", ", names)));
        }
        return clip(description.toString(), MAX_DESCRIPTION_CHARS);
    }

    /** Replaces control characters other than newlines and tabs, strips and clips. */
    static String clean(String text, int limit) {
        if (text == null) {
            return "";
        }
        var cleaned = new StringBuilder(Math.min(text.length(), limit + 1));
        text.codePoints().forEach(point -> {
            if (point == '\n' || point == '\t' || !Character.isISOControl(point)) {
                cleaned.appendCodePoint(point);
            } else {
                cleaned.append(' ');
            }
        });
        return clip(cleaned.toString().strip(), limit);
    }

    /** Clips to {@code limit} UTF-16 units without splitting a surrogate pair. */
    static String clip(String text, int limit) {
        if (text == null) {
            return "";
        }
        if (text.length() <= limit) {
            return text;
        }
        int end = limit;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }

    private static Map<String, Object> convert(Object raw, int depth, String path, List<String> untyped) {
        if (depth > MAX_DEPTH || !(raw instanceof Map<?, ?> source)) {
            return null;
        }
        String type = resolveType(source);
        if (type == null) {
            return null;
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("type", type);
        if (source.get("description") instanceof String description && !description.isBlank()) {
            out.put("description", clean(description, MAX_FIELD_TEXT_CHARS));
        }
        if (source.get("title") instanceof String title && !title.isBlank()) {
            out.put("title", clean(title, 200));
        }
        List<Object> values = enumValues(type, source.get("enum"));
        if (values != null) {
            out.put("enum", values);
        }
        switch (type) {
            case "object" -> convertObject(source, out, depth, path, untyped);
            case "array" -> {
                if (source.containsKey("items") && source.get("items") instanceof Map<?, ?>) {
                    Map<String, Object> items = convert(source.get("items"), depth + 1, path + "[]", untyped);
                    if (items != null) {
                        out.put("items", items);
                    }
                }
                copyBounds(source, out, "minItems", "maxItems", true);
            }
            case "string" -> copyBounds(source, out, "minLength", "maxLength", true);
            case "integer", "number" -> copyBounds(source, out, "minimum", "maximum", false);
            default -> {
                // boolean and null carry no further constraints.
            }
        }
        return out;
    }

    private static void convertObject(
            Map<?, ?> source,
            Map<String, Object> out,
            int depth,
            String path,
            List<String> untyped) {
        boolean dropped = false;
        if (source.get("properties") instanceof Map<?, ?> rawProperties) {
            var properties = new LinkedHashMap<String, Object>();
            int count = 0;
            for (var entry : rawProperties.entrySet()) {
                if (!(entry.getKey() instanceof String name) || name.isEmpty()) {
                    dropped = true;
                    continue;
                }
                String childPath = path.isEmpty() ? name : path + "." + name;
                if (++count > MAX_PROPERTIES) {
                    dropped = true;
                    untyped.add(childPath);
                    continue;
                }
                Map<String, Object> child = convert(entry.getValue(), depth + 1, childPath, untyped);
                if (child == null) {
                    dropped = true;
                    untyped.add(childPath);
                } else {
                    properties.put(name, child);
                }
            }
            out.put("properties", properties);
        }
        if (source.get("required") instanceof List<?> rawRequired) {
            var required = new ArrayList<String>();
            var seen = new HashSet<String>();
            for (Object name : rawRequired) {
                if (name instanceof String text && !text.isEmpty() && seen.add(text)
                        && required.size() < MAX_PROPERTIES) {
                    required.add(text);
                }
            }
            if (!required.isEmpty()) {
                out.put("required", required);
            }
        }
        Object additional = source.get("additionalProperties");
        if (Boolean.FALSE.equals(additional) && !dropped) {
            out.put("additionalProperties", false);
        } else if (additional instanceof Map<?, ?>) {
            Map<String, Object> schema = convert(additional, depth + 1, path + ".*", untyped);
            if (schema != null && !dropped) {
                out.put("additionalProperties", schema);
            }
        }
    }

    private static String resolveType(Map<?, ?> source) {
        Object declared = source.get("type");
        if (declared instanceof String type) {
            return TYPES.contains(type) ? type : null;
        }
        if (declared instanceof List<?> candidates) {
            String nullable = null;
            for (Object candidate : candidates) {
                if (candidate instanceof String type && TYPES.contains(type)) {
                    if ("null".equals(type)) {
                        nullable = type;
                    } else {
                        return type;
                    }
                }
            }
            return nullable;
        }
        if (declared != null) {
            return null;
        }
        if (source.get("properties") instanceof Map<?, ?>) {
            return "object";
        }
        if (source.get("items") instanceof Map<?, ?>) {
            return "array";
        }
        if (source.get("enum") instanceof List<?> values && !values.isEmpty()) {
            return scalarType(values);
        }
        Object constant = source.get("const");
        if (constant != null) {
            return scalarType(List.of(constant));
        }
        return null;
    }

    private static String scalarType(List<?> values) {
        String type = null;
        for (Object value : values) {
            String candidate;
            if (value instanceof String) {
                candidate = "string";
            } else if (value instanceof Boolean) {
                candidate = "boolean";
            } else if (value instanceof Number number) {
                candidate = isIntegral(number) ? "integer" : "number";
            } else {
                return null;
            }
            if (type == null || type.equals(candidate)) {
                type = candidate;
            } else if (Set.of(type, candidate).equals(Set.of("integer", "number"))) {
                type = "number";
            } else {
                return null;
            }
        }
        return type;
    }

    private static List<Object> enumValues(String type, Object raw) {
        if (!(raw instanceof List<?> values) || values.isEmpty()) {
            return null;
        }
        var out = new ArrayList<Object>();
        var seen = new HashSet<String>();
        for (Object value : values) {
            if (out.size() >= MAX_ENUM_VALUES) {
                return null;
            }
            String key;
            if (value instanceof String text && "string".equals(type)) {
                key = "s:" + text;
            } else if (value instanceof Boolean flag && "boolean".equals(type)) {
                key = "b:" + flag;
            } else if (value instanceof Number number && ("number".equals(type)
                    || "integer".equals(type) && isIntegral(number))) {
                key = "n:" + decimal(number).stripTrailingZeros().toPlainString();
            } else {
                // A value outside the resolved type (often null for a nullable enum) is simply not listed.
                continue;
            }
            if (seen.add(key)) {
                out.add(value);
            }
        }
        return out.isEmpty() ? null : out;
    }

    private static void copyBounds(
            Map<?, ?> source,
            Map<String, Object> out,
            String minimum,
            String maximum,
            boolean integral) {
        BigDecimal low = bound(source.get(minimum), integral);
        BigDecimal high = bound(source.get(maximum), integral);
        if (low != null && high != null && low.compareTo(high) > 0) {
            return;
        }
        if (low != null) {
            out.put(minimum, integral ? low.longValueExact() : low);
        }
        if (high != null) {
            out.put(maximum, integral ? high.longValueExact() : high);
        }
    }

    private static BigDecimal bound(Object value, boolean integral) {
        if (!(value instanceof Number number)) {
            return null;
        }
        BigDecimal decimal;
        try {
            decimal = decimal(number);
        } catch (NumberFormatException invalid) {
            return null;
        }
        if (integral && (decimal.signum() < 0 || decimal.stripTrailingZeros().scale() > 0
                || decimal.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0)) {
            return null;
        }
        return decimal;
    }

    private static boolean isIntegral(Number number) {
        try {
            return decimal(number).stripTrailingZeros().scale() <= 0;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private static BigDecimal decimal(Number number) {
        return new BigDecimal(number.toString());
    }
}
