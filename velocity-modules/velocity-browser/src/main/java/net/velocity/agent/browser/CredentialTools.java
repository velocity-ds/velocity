package net.velocity.agent.browser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import net.velocity.agent.JsonSchema;
import net.velocity.agent.ToolRegistry;
import net.velocity.agent.security.BrowserRejectedException;

/** No plaintext input or output contract exists for saved credentials. */
public final class CredentialTools {

    public static final List<String> NAMES = List
            .of("credential_find", "browser_fill_credential", "browser_submit_login");

    private CredentialTools() {
    }

    public static ToolRegistry register(ToolRegistry registry, BrowserService browser) {
        Objects.requireNonNull(browser);
        registry.register(
                definition(
                        "credential_find",
                        BrowserPrompts.CREDENTIAL_FIND_DESCRIPTION,
                        browser::findCredential));
        registry.register(
                definition(
                        "browser_fill_credential",
                        BrowserPrompts.BROWSER_FILL_CREDENTIAL_DESCRIPTION,
                        browser::fillCredential));
        return registry.register(
                definition(
                        "browser_submit_login",
                        BrowserPrompts.BROWSER_SUBMIT_LOGIN_DESCRIPTION,
                        browser::submitLogin));
    }

    private static ToolRegistry.Definition definition(
            String name,
            String description,
            ToolRegistry.Handler handler) {
        return new ToolRegistry.Definition(
                name,
                description,
                inputSchema(name),
                "credential_find".equals(name) ? findOutputSchema() : mutationOutputSchema(),
                "credential_find".equals(name) ? "system.read" : "browser.interaction",
                true,
                true,
                false,
                0,
                ToolRegistry.MonitoringAccess.denied,
                (context, input) -> {
                    try {
                        return handler.execute(context, input);
                    } catch (BrowserRejectedException rejected) {
                        return BrowserTools.rejection(rejected);
                    } catch (ToolRegistry.UncertainToolException uncertain) {
                        throw uncertain;
                    } catch (Exception uncertain) {
                        // Browser exceptions can contain field values and request URLs. Do not retain a cause.
                        throw new IllegalStateException(
                                "Credential browser operation did not complete; inspect its outcome before continuing");
                    }
                });
    }

    public static void validateInput(String name, Map<String, Object> input) {
        try {
            JsonSchema.validate(inputSchema(name), input);
        } catch (IllegalArgumentException invalid) {
            throw new BrowserRejectedException(
                    "invalid_request",
                    "Credential input failed schema validation");
        }
        if ("browser_fill_credential".equals(name) && !input.containsKey("username_index")
                && !input.containsKey("password_index")) {
            throw new BrowserRejectedException(
                    "invalid_request",
                    "At least one observed credential field index is required");
        }
        if (input.containsKey("username_index") && input.containsKey("password_index")
                && ((Number) input.get("username_index"))
                        .longValue() == ((Number) input.get("password_index")).longValue()) {
            throw new BrowserRejectedException(
                    "invalid_request",
                    "Username and password fields must be distinct");
        }
    }

    private static Map<String, Object> inputSchema(String name) {
        if (!NAMES.contains(name)) {
            throw new IllegalArgumentException("Unknown credential tool");
        }
        var fields = new LinkedHashMap<String, Object>();
        for (String field : List.of("generation", "state_id")) {
            fields.put(field, text(128));
        }
        var required = new ArrayList<>(fields.keySet());
        fields.put("session_id", text(128));
        if ("credential_find".equals(name)) {
            fields.put("account_label", text(120));
        } else {
            fields.put("credential_id", text(128));
            fields.put("revision", revision());
            required.addAll(List.of("credential_id", "revision"));
            if ("browser_fill_credential".equals(name)) {
                fields.put("username_index", index());
                fields.put("password_index", index());
            } else {
                fields.put("index", index());
                required.add("index");
            }
        }
        return object(fields, required);
    }

    private static Map<String, Object> findOutputSchema() {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("credential_id", text(128));
        metadata.put("revision", revision());
        metadata.put("service", text(120));
        metadata.put("account_label", text(120));
        for (String policy : List.of(
                "credential_origins",
                "post_login_origins",
                "application_origins",
                "callback_urls",
                "resource_origins")) {
            metadata.put(policy, Map.of("type", "array", "items", text(4096), "maxItems", 16));
        }
        var fields = new LinkedHashMap<String, Object>();
        for (String field : List.of("status", "code", "session_id", "generation")) {
            fields.put(field, text(128));
        }
        fields.put("message", text(4096));
        fields.put("action_outcome", Map.of("type", "string", "enum", List.of("none")));
        fields.put("content_trust", Map.of("type", "string", "enum", List.of("untrusted_data")));
        fields.put("truncated", Map.of("type", "boolean"));
        fields.put("recovery", Map.of("type", "array", "items", text(4096), "maxItems", 16));
        fields.put(
                "matches",
                Map.of(
                        "type",
                        "array",
                        "items",
                        object(metadata, List.copyOf(metadata.keySet())),
                        "maxItems",
                        500));
        return object(fields, List.of("status", "action_outcome", "content_trust"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mutationOutputSchema() {
        var browserSchema = BrowserTools.outputSchema();
        var fields = new LinkedHashMap<>((Map<String, Object>) browserSchema.get("properties"));
        fields.put("status", Map.of("type", "string", "enum", List.of("filled", "submitted", "rejected")));
        return object(fields, List.of("status", "action_outcome", "content_trust"));
    }

    private static Map<String, Object> revision() {
        return Map.of("type", "integer", "minimum", 1, "maximum", 9007199254740991L);
    }

    private static Map<String, Object> index() {
        return Map.of("type", "integer", "minimum", 1, "maximum", 2147483647);
    }

    private static Map<String, Object> text(int max) {
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
