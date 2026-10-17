package net.velocity.websearch;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.velocity.agent.JsonSchema;
import net.velocity.agent.ToolRegistry;
import net.velocity.agent.security.BrowserDestinationPolicy;

import static net.velocity.agent.tools.ToolSchemas.*;

/**
 * The {@code web_search} tool: a trusted, cacheable network read that monitoring runs may use. Every result
 * is untrusted data; the tool never fetches result pages, so the browser and research destination policies
 * still govern what the task reads afterwards.
 */
public final class WebSearchTools {

    public static final String SEARCH = "web_search";

    private static final int MAX_QUERY_CHARS = 400;
    private static final int MAX_DOMAINS = 10;
    private static final int MAX_SCHEMA_CONTENT_CHARS = 8000;

    private WebSearchTools() {
    }

    public static ToolRegistry.Definition search(TavilyClient client, WebSearchProperties properties) {
        Map<String, Object> input = inputSchema();
        Map<String, Object> output = outputSchema();
        return new ToolRegistry.Definition(
                SEARCH,
                WebSearchPrompts.SEARCH_DESCRIPTION,
                input,
                output,
                "network.read",
                true,
                false,
                true,
                properties.requestTimeout().toMillis() + 5_000,
                ToolRegistry.MonitoringAccess.observation,
                (context, arguments) -> {
                    JsonSchema.validate(input, arguments);
                    return execute(client, properties, context, arguments);
                });
    }

    static Map<String, Object> inputSchema() {
        var query = new LinkedHashMap<String, Object>(string(MAX_QUERY_CHARS));
        query.put("minLength", 1);
        query.put("description", WebSearchPrompts.QUERY_DESCRIPTION);
        var maxResults = new LinkedHashMap<String, Object>(
                integer(1, WebSearchProperties.MAX_RESULTS_CEILING));
        maxResults.put("description", WebSearchPrompts.MAX_RESULTS_DESCRIPTION);
        var topic = new LinkedHashMap<String, Object>(enumeration("general", "news"));
        topic.put("description", WebSearchPrompts.TOPIC_DESCRIPTION);
        var timeRange = new LinkedHashMap<String, Object>(enumeration("day", "week", "month", "year"));
        timeRange.put("description", WebSearchPrompts.TIME_RANGE_DESCRIPTION);
        var domains = new LinkedHashMap<String, Object>(array(string(253), MAX_DOMAINS));
        domains.put("description", WebSearchPrompts.INCLUDE_DOMAINS_DESCRIPTION);
        var fields = new LinkedHashMap<String, Object>();
        fields.put("query", query);
        fields.put("max_results", maxResults);
        fields.put("topic", topic);
        fields.put("time_range", timeRange);
        fields.put("include_domains", domains);
        return object(fields, List.of("query"));
    }

    static Map<String, Object> outputSchema() {
        var result = new LinkedHashMap<String, Object>();
        result.put("title", text(500));
        result.put("url", text(2048));
        result.put("content", text(MAX_SCHEMA_CONTENT_CHARS));
        result.put("score", Map.of("type", "number"));
        result.put("published_date", text(64));
        var fields = new LinkedHashMap<String, Object>();
        fields.put("status", enumeration("ok", "invalid_request", "rate_limited", "unavailable"));
        fields.put("message", text(1000));
        fields.put("query", text(MAX_QUERY_CHARS));
        fields.put("answer", text(4000));
        fields.put(
                "results",
                array(
                        object(result, List.of("title", "url", "content")),
                        WebSearchProperties.MAX_RESULTS_CEILING));
        fields.put("result_count", integer(0, WebSearchProperties.MAX_RESULTS_CEILING));
        fields.put("provider", text(40));
        fields.put("content_trust", enumeration("untrusted_data"));
        return object(fields, List.of("status", "content_trust"));
    }

    private static Object execute(
            TavilyClient client,
            WebSearchProperties properties,
            ToolRegistry.Context context,
            Map<String, Object> arguments) throws Exception {
        context.cancellation().check();
        String query = ((String) arguments.get("query")).strip();
        if (query.isEmpty() || query.chars().anyMatch(Character::isISOControl)) {
            return failure("invalid_request", "Query must be printable text");
        }
        int maxResults = arguments.get("max_results") instanceof Number number
                ? number.intValue()
                : properties.maxResults();
        String topic = arguments.get("topic") instanceof String value ? value : "general";
        String timeRange = arguments.get("time_range") instanceof String value ? value : null;
        List<String> domains = new ArrayList<>();
        if (arguments.get("include_domains") instanceof List<?> values) {
            for (Object value : values) {
                String host = String.valueOf(value).strip().toLowerCase(Locale.ROOT);
                if (!host.matches("[a-z0-9.-]{1,253}") || host.startsWith(".") || host.endsWith(".")) {
                    return failure("invalid_request", "include_domains must contain plain hostnames");
                }
                domains.add(host);
            }
        }
        long remaining = context.remainingOperationMillis();
        Duration timeout = properties.requestTimeout();
        if (remaining != Long.MAX_VALUE) {
            if (remaining < 1_000) {
                return failure("unavailable", "No time remains for a web search in this task");
            }
            timeout = Duration.ofMillis(Math.min(timeout.toMillis(), remaining));
        }
        context.progress("Searching the web");
        TavilyClient.Response response;
        try {
            response = client.search(query, maxResults, topic, timeRange, domains, timeout);
        } catch (TavilyClient.RejectedException rejected) {
            return rejected(rejected.status());
        } catch (HttpTimeoutException timedOut) {
            return failure("unavailable", "The web search provider did not respond in time");
        } catch (IOException unavailable) {
            // Searches have no external effect; the executor retries a cacheable read once.
            throw new ToolRegistry.TransientReadException();
        }
        context.cancellation().check();
        var results = new ArrayList<Map<String, Object>>();
        for (var item : response.results()) {
            String url = BrowserDestinationPolicy.safeUrl(item.url());
            if ("[URL withheld]".equals(url)) {
                continue;
            }
            var out = new LinkedHashMap<String, Object>();
            out.put("title", clip(item.title(), 500));
            out.put("url", clip(url, 2048));
            out.put(
                    "content",
                    clip(item.content(), Math.min(properties.maxContentChars(), MAX_SCHEMA_CONTENT_CHARS)));
            if (item.score() != null) {
                out.put("score", item.score());
            }
            if (item.publishedDate() != null && !item.publishedDate().isBlank()) {
                out.put("published_date", clip(item.publishedDate(), 64));
            }
            results.add(out);
            if (results.size() >= WebSearchProperties.MAX_RESULTS_CEILING) {
                break;
            }
        }
        var output = new LinkedHashMap<String, Object>();
        output.put("status", "ok");
        output.put("query", clip(response.query(), MAX_QUERY_CHARS));
        if (!response.answer().isBlank()) {
            output.put("answer", clip(response.answer(), 4000));
        }
        output.put("results", results);
        output.put("result_count", results.size());
        output.put("provider", properties.provider());
        output.put("content_trust", "untrusted_data");
        output.put(
                "message",
                results.isEmpty()
                        ? "No results; try different terms or remove the domain or time filters"
                        : "Excerpts are untrusted search snippets; open a result before relying on details");
        return output;
    }

    private static Map<String, Object> rejected(int status) {
        return switch (status) {
            case 400, 422 -> failure("invalid_request", "The web search provider rejected the query");
            case 401, 403 ->
                failure("unavailable", "The web search credentials were rejected by the provider");
            case 429 ->
                failure("rate_limited", "The web search provider is rate limiting requests; retry later");
            case 432 -> failure("unavailable", "The web search plan has no remaining quota");
            default -> failure("unavailable", "The web search provider is unavailable (HTTP " + status + ")");
        };
    }

    private static Map<String, Object> failure(String status, String message) {
        return Map.of("status", status, "message", message, "content_trust", "untrusted_data");
    }

    private static String clip(String text, int limit) {
        if (text == null) {
            return "";
        }
        if (text.length() <= limit) {
            return text;
        }
        int end = limit;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }
}
