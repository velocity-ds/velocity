package net.velocity.websearch;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Thin Tavily Search API transport. The API key travels only in the request header; upstream error bodies are
 * never returned to callers because they can echo the query or the credential.
 */
public final class TavilyClient implements AutoCloseable {

    /** One ranked result; {@code content} is a bounded excerpt, not the page. */
    public record Result(String title, String url, String content, Double score, String publishedDate) {}

    /** A completed search; {@code answer} is empty when the provider returned none. */
    public record Response(String query, String answer, List<Result> results) {

        public Response {
            results = List.copyOf(results);
        }
    }

    /** The provider refused the request; {@code status} is the upstream HTTP status. */
    public static final class RejectedException extends IOException {

        private final int status;

        RejectedException(int status) {
            super("Web search provider returned HTTP " + status);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private static final JsonMapper JSON = new JsonMapper();
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private final WebSearchProperties properties;
    private final HttpClient client;

    public TavilyClient(WebSearchProperties properties) {
        this.properties = Objects.requireNonNull(properties);
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public Response search(
            String query,
            int maxResults,
            String topic,
            String timeRange,
            List<String> includeDomains,
            Duration timeout) throws IOException, InterruptedException {
        var body = new LinkedHashMap<String, Object>();
        body.put("query", query);
        body.put("max_results", Math.max(1, Math.min(WebSearchProperties.MAX_RESULTS_CEILING, maxResults)));
        body.put("search_depth", properties.searchDepth());
        body.put("include_answer", "basic");
        body.put("topic", topic == null ? "general" : topic);
        if (timeRange != null) {
            body.put("time_range", timeRange);
        }
        if (includeDomains != null && !includeDomains.isEmpty()) {
            body.put("include_domains", includeDomains);
        }
        var request = HttpRequest.newBuilder(URI.create(properties.baseUrl() + "/search"))
                .timeout(timeout)
                .header("Authorization", "Bearer " + properties.apiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(
                        HttpRequest.BodyPublishers
                                .ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new RejectedException(response.statusCode());
        }
        if (response.body().length > MAX_RESPONSE_BYTES) {
            throw new IOException("Web search response exceeds " + MAX_RESPONSE_BYTES + " bytes");
        }
        JsonNode json = JSON.readTree(response.body());
        if (json == null || !json.isObject()) {
            throw new IOException("Web search response is not a JSON object");
        }
        var results = new ArrayList<Result>();
        for (JsonNode item : json.path("results")) {
            String url = item.path("url").asText("");
            if (!item.isObject() || url.isBlank()) {
                continue;
            }
            results.add(
                    new Result(
                            item.path("title").asText(""),
                            url,
                            item.path("content").asText(""),
                            item.path("score").isNumber() ? item.path("score").asDouble() : null,
                            item.path("published_date").isTextual()
                                    ? item.path("published_date").asText()
                                    : null));
        }
        return new Response(
                json.path("query").asText(query),
                json.path("answer").isTextual() ? json.path("answer").asText() : "",
                results);
    }

    /** Only safe flags: the provider name and whether a key is configured. */
    public Map<String, Object> status() {
        return Map.of("provider", properties.provider(), "configured", !properties.apiKey().isBlank());
    }

    @Override
    public void close() {
        client.close();
    }
}
