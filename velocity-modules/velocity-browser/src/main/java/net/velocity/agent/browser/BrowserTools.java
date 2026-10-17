package net.velocity.agent.browser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import net.velocity.agent.JsonSchema;
import net.velocity.agent.ToolRegistry;
import net.velocity.agent.security.BrowserRejectedException;

/** Explicit Browser Use tools; upstream discovery never grants execution authority. */
public final class BrowserTools {

    private static final List<String> CORE_NAMES = List.of(
            "browser_navigate",
            "browser_state",
            "browser_click",
            "browser_type",
            "browser_scroll",
            "browser_navigate_back",
            "browser_press_key",
            "browser_select_option",
            "browser_tabs",
            "browser_wait_for",
            "browser_close");
    private static final List<String> NAMES = java.util.stream.Stream
            .concat(CORE_NAMES.stream(), java.util.stream.Stream.of("browser_read_page")).toList();
    /** Every registered browser tool, including the screenshot tool registered by {@link ScreenshotTools}. */
    private static final List<String> SUPPORTED = java.util.stream.Stream
            .concat(NAMES.stream(), java.util.stream.Stream.of(ScreenshotTools.NAME)).toList();

    private BrowserTools() {
    }

    public static List<String> coreToolNames() {
        return CORE_NAMES;
    }

    public static List<String> supportedToolNames() {
        return SUPPORTED;
    }

    public static ToolRegistry register(ToolRegistry registry, BrowserService service) {
        for (String name : NAMES) {
            registry.register(
                    new ToolRegistry.Definition(
                            name,
                            BrowserPrompts.browserToolDescription(name),
                            inputSchema(name),
                            name.equals("browser_read_page") ? readOutputSchema() : outputSchema(),
                            List.of("browser_state", "browser_wait_for", "browser_read_page").contains(name)
                                    ? "system.read"
                                    : "browser.interaction",
                            true,
                            true,
                            false,
                            0,
                            monitoring(name),
                            monitoringInput(name),
                            new ResultHandler() {

                                @Override
                                public Object execute(ToolRegistry.Context context, Map<String, Object> input)
                                        throws Exception {
                                    try {
                                        return service.call(context, name, input);
                                    } catch (BrowserRejectedException rejected) {
                                        var result = new LinkedHashMap<>(rejection(rejected));
                                        result.put("is_error", true);
                                        return result;
                                    }
                                }

                                @Override
                                public boolean failed(Object output) {
                                    return output instanceof Map<?, ?> result
                                            && Boolean.TRUE.equals(result.get("is_error"));
                                }
                            }));
        }
        return registry;
    }

    private interface ResultHandler
            extends
                ToolRegistry.FailureAwareHandler,
                ToolRegistry.CompleteResultHandler {}

    public static void validateInput(String name, Map<String, Object> input) {
        try {
            JsonSchema.validate(inputSchema(name), input);
        } catch (IllegalArgumentException invalid) {
            throw new BrowserRejectedException("invalid_request", "Browser Use input failed validation.");
        }
        if (name.equals("browser_tabs")) {
            var action = input.get("action");
            if (List.of("select", "close").contains(action) && !input.containsKey("page_id")
                    || "new".equals(action) && !input.containsKey("url")
                    || input.containsKey("url") && !"new".equals(action)
                    || input.containsKey("page_id") && !List.of("select", "close").contains(action)) {
                throw new BrowserRejectedException("invalid_request", "Invalid tab arguments.");
            }
        }
        if (input.containsKey("session_id") != input.containsKey("generation")) {
            throw new BrowserRejectedException(
                    "invalid_request",
                    "Supply both browser session and generation.");
        }
        if (name.equals("browser_read_page")
                && (input.containsKey("source_id") && input.containsKey("section")
                        || !input.containsKey("source_id") && input.get("offset") instanceof Number offset
                                && offset.intValue() != 0
                        || input.get("section") instanceof String section && section.isBlank())) {
            throw new BrowserRejectedException(
                    "invalid_request",
                    "Start at offset zero; continue with source_id and no section.");
        }
    }

    public static Map<String, Object> inputSchema(String name) {
        if (!NAMES.contains(name)) {
            throw new IllegalArgumentException("Unsupported Browser Use runtime tool");
        }
        var fields = new LinkedHashMap<String, Object>();
        fields.put("session_id", string(128));
        fields.put("generation", string(128));
        var required = new ArrayList<String>();
        if (!name.equals("browser_navigate")) {
            required.addAll(List.of("session_id", "generation"));
        }
        switch (name) {
            case "browser_navigate" -> {
                fields.put("url", string(8192));
                required.add("url");
            }
            case "browser_click", "browser_type", "browser_select_option" -> {
                fields.put("state_id", string(128));
                fields.put("index", Map.of("type", "integer", "minimum", 1, "maximum", 1000000));
                required.addAll(List.of("state_id", "index"));
                if (name.equals("browser_type") || name.equals("browser_select_option")) {
                    fields.put(
                            "text",
                            name.equals("browser_select_option")
                                    ? string(8192)
                                    : Map.of("type", "string", "maxLength", 8192));
                    required.add("text");
                }
            }
            case "browser_scroll" -> {
                fields.put("direction", Map.of("type", "string", "enum", List.of("up", "down")));
                required.add("direction");
            }
            case "browser_press_key" -> {
                fields.put("key", string(128));
                required.add("key");
            }
            case "browser_tabs" -> {
                fields.put(
                        "action",
                        Map.of("type", "string", "enum", List.of("list", "new", "select", "close")));
                required.add("action");
                fields.put("page_id", string(512));
                fields.put("url", string(8192));
            }
            case "browser_wait_for" -> {
                fields.put("time", Map.of("type", "number", "minimum", 0, "maximum", 300));
                required.add("time");
            }
            case "browser_read_page" -> {
                fields.put("section", string(160));
                fields.put("source_id", string(128));
                fields.put(
                        "offset",
                        Map.of("type", "integer", "minimum", 0, "maximum", BrowserService.MAX_SOURCE_CHARS));
                fields.put("limit", Map.of("type", "integer", "minimum", 1, "maximum", 24000));
            }
            default -> {
            }
        }
        return object(fields, required);
    }

    public static Map<String, Object> outputSchema() {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("status", Map.of("type", "string", "enum", List.of("ok", "error", "rejected")));
        for (String name : List.of(
                "session_id",
                "generation",
                "state_id",
                "code",
                "content_trust",
                "url",
                "message",
                "action_outcome")) {
            fields.put(name, string(8192));
        }
        fields.put("title", Map.of("type", "string", "maxLength", 4096));
        fields.put("text", Map.of("type", "string"));
        fields.put("truncated", Map.of("type", "boolean"));
        fields.put("is_error", Map.of("type", "boolean"));
        fields.put("recovery", Map.of("type", "array", "items", string(128)));
        fields.put(
                "tabs",
                Map.of(
                        "type",
                        "array",
                        "items",
                        object(
                                Map.of(
                                        "page_id",
                                        string(512),
                                        "url",
                                        string(8192),
                                        "title",
                                        Map.of("type", "string", "maxLength", 4096)),
                                List.of("page_id", "url", "title"))));
        return object(fields, List.of("status", "content_trust", "truncated"));
    }

    /** Separate from frozen action schemas, including schemas reused by credential tools. */
    public static Map<String, Object> readOutputSchema() {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("status", Map.of("type", "string", "enum", List.of("ok", "error", "rejected")));
        for (String name : List.of(
                "session_id",
                "generation",
                "source_id",
                "code",
                "content_trust",
                "url",
                "message",
                "action_outcome",
                "observed_at")) {
            fields.put(name, string(8192));
        }
        fields.put("title", Map.of("type", "string", "maxLength", 4096));
        fields.put("text", Map.of("type", "string", "maxLength", 24000));
        fields.put(
                "coverage",
                Map.of(
                        "type",
                        "string",
                        "enum",
                        List.of("ok", "partial", "truncated", "missing", "blocked")));
        fields.put("coverage_note", Map.of("type", "string", "maxLength", 2000));
        for (String name : List.of("offset", "total_chars")) {
            fields.put(
                    name,
                    Map.of("type", "integer", "minimum", 0, "maximum", BrowserService.MAX_SOURCE_CHARS));
        }
        fields.put(
                "next_offset",
                Map.of("type", "integer", "minimum", -1, "maximum", BrowserService.MAX_SOURCE_CHARS));
        for (String name : List.of("truncated", "is_error", "has_more")) {
            fields.put(name, Map.of("type", "boolean"));
        }
        fields.put("recovery", Map.of("type", "array", "items", string(128)));
        return object(fields, List.of("status", "content_trust", "truncated"));
    }

    private static ToolRegistry.MonitoringAccess monitoring(String name) {
        if (List.of("browser_state", "browser_wait_for", "browser_read_page").contains(name)) {
            return ToolRegistry.MonitoringAccess.observation;
        }
        return monitoringInput(name) != null
                ? ToolRegistry.MonitoringAccess.restricted
                : ToolRegistry.MonitoringAccess.denied;
    }

    /**
     * Unattended monitoring may navigate and close, and may list, select or close tabs; opening a new tab is
     * excluded.
     */
    private static Predicate<Map<String, Object>> monitoringInput(String name) {
        if ("browser_tabs".equals(name)) {
            return input -> input != null && input.get("action") instanceof String action
                    && Set.of("list", "select", "close").contains(action);
        }
        return List.of("browser_navigate", "browser_navigate_back", "browser_close").contains(name)
                ? input -> true
                : null;
    }

    public static Map<String, Object> rejection(BrowserRejectedException rejected) {
        var result = new LinkedHashMap<String, Object>();
        result.put("status", "rejected");
        result.put("code", rejected.code());
        result.put("message", rejected.getMessage());
        result.put("action_outcome", "none");
        result.put("content_trust", "untrusted_data");
        result.put("truncated", false);
        result.put(
                "recovery",
                rejected.sessionBinding().isEmpty()
                        ? List.of("inspect_current_state", "report_blocker")
                        : List.of("copy_returned_session_binding", "browser_state"));
        result.putAll(rejected.sessionBinding());
        return result;
    }

    private static Map<String, Object> string(int max) {
        return Map.of("type", "string", "minLength", 1, "maxLength", max);
    }

    private static Map<String, Object> object(Map<String, Object> fields, List<String> required) {
        return Map.of(
                "type",
                "object",
                "properties",
                fields,
                "required",
                required,
                "additionalProperties",
                false);
    }
}
