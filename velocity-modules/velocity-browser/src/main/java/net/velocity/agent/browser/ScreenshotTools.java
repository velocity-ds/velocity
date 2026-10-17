package net.velocity.agent.browser;

import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.velocity.ApplicationTime;
import net.velocity.agent.JsonSchema;
import net.velocity.agent.ToolRegistry;
import net.velocity.agent.persistence.ConflictException;
import net.velocity.agent.persistence.ExecutionRepository;
import net.velocity.agent.research.OutputFileTools;
import net.velocity.agent.research.ResearchService;
import net.velocity.agent.security.BrowserRejectedException;

/**
 * Captures the owned current page as a PNG output file and stages it for delivery with the final answer. The
 * image bytes never enter the model-visible result; only the saved file's metadata does.
 */
public final class ScreenshotTools {

    public static final String NAME = "browser_screenshot";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
            .withZone(ApplicationTime.zone());

    private ScreenshotTools() {
    }

    public static ToolRegistry register(
            ToolRegistry registry,
            BrowserService browser,
            ResearchService research,
            ExecutionRepository executions,
            Clock clock) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("session_id", string(128));
        fields.put("generation", string(128));
        fields.put("full_page", Map.of("type", "boolean"));
        fields.put("filename", string(100));
        fields.put("description", Map.of("type", "string", "maxLength", 300));
        var schema = object(fields, List.of("session_id", "generation"));
        var output = new LinkedHashMap<String, Object>();
        output.put("status", Map.of("type", "string", "enum", List.of("staged", "superseded", "rejected")));
        for (String name : List.of("session_id", "generation", "file_id", "code", "content_trust")) {
            output.put(name, string(256));
        }
        output.put("filename", string(100));
        output.put("media_type", string(150));
        output.put("byte_size", Map.of("type", "integer", "minimum", 0, "maximum", Long.MAX_VALUE));
        output.put("goal_generation", Map.of("type", "integer", "minimum", 1, "maximum", Long.MAX_VALUE));
        output.put("current_selection", Map.of("type", "boolean"));
        output.put("url", Map.of("type", "string", "maxLength", 8192));
        output.put("title", Map.of("type", "string", "maxLength", 4096));
        output.put("message", Map.of("type", "string", "maxLength", 1000));
        output.put("is_error", Map.of("type", "boolean"));
        return registry.register(
                new ToolRegistry.Definition(
                        NAME,
                        BrowserPrompts.BROWSER_SCREENSHOT_DESCRIPTION,
                        schema,
                        object(output, List.of("status")),
                        "repository.write",
                        true,
                        true,
                        false,
                        0,
                        ToolRegistry.MonitoringAccess.denied,
                        new Handler(browser, research, executions, clock, schema)));
    }

    private record Handler(
            BrowserService browser,
            ResearchService research,
            ExecutionRepository executions,
            Clock clock,
            Map<String, Object> schema) implements ToolRegistry.FailureAwareHandler {

        @Override
        public Object execute(ToolRegistry.Context context, Map<String, Object> input) throws Exception {
            JsonSchema.validate(schema, input);
            try {
                var shot = browser.screenshot(
                        context,
                        input,
                        Boolean.TRUE.equals(input.get("full_page")),
                        research.maxOutputFileBytes());
                String filename = input.get("filename") instanceof String value
                        ? value
                        : "screenshot-" + STAMP.format(clock.instant()) + ".png";
                if (!filename.toLowerCase(Locale.ROOT).endsWith(".png")) {
                    filename = filename + ".png";
                }
                String description = input.get("description") instanceof String value && !value.isBlank()
                        ? value
                        : "Screenshot of " + (shot.title().isBlank() ? shot.pageUrl() : shot.title());
                var provenance = new LinkedHashMap<String, String>();
                provenance.put("source", "browser_screenshot");
                provenance.put("session_id", shot.sessionId());
                provenance.put("generation", shot.generation());
                provenance.put("page_url", shot.pageUrl());
                provenance.put("full_page", Boolean.toString(Boolean.TRUE.equals(input.get("full_page"))));
                var saved = research.saveOutputFile(context, filename, shot.bytes(), description, provenance);
                var result = new LinkedHashMap<String, Object>();
                result.put("session_id", shot.sessionId());
                result.put("generation", shot.generation());
                result.putAll(OutputFileTools.staged(executions, research, context, saved));
                result.put("url", shot.pageUrl());
                result.put("title", shot.title());
                result.put("content_trust", "untrusted_data");
                return result;
            } catch (BrowserRejectedException rejected) {
                var result = new LinkedHashMap<String, Object>();
                result.put("status", "rejected");
                result.put("code", rejected.code());
                result.put("message", rejected.getMessage());
                result.put("content_trust", "untrusted_data");
                result.put("is_error", true);
                result.putAll(rejected.sessionBinding());
                return result;
            } catch (ConflictException superseded) {
                return Map.of(
                        "status",
                        "superseded",
                        "message",
                        "A task correction superseded this screenshot");
            } catch (ResearchService.ResearchException failed) {
                if ("stale_goal".equals(failed.status())) {
                    return Map.of("status", "superseded", "message", failed.getMessage());
                }
                return rejected(failed.status(), failed.getMessage());
            } catch (IllegalArgumentException invalid) {
                return rejected("invalid_request", invalid.getMessage());
            }
        }

        @Override
        public boolean failed(Object output) {
            return output instanceof Map<?, ?> result && Boolean.TRUE.equals(result.get("is_error"));
        }
    }

    private static Map<String, Object> rejected(String code, String message) {
        var result = new LinkedHashMap<String, Object>();
        result.put("status", "rejected");
        result.put("code", code);
        result.put("message", message == null ? "Invalid request" : message);
        result.put("is_error", true);
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
