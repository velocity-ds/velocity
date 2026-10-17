package net.velocity.websearch;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Web search settings. Defaults live only in {@code application.properties}; an enabled feature without an
 * API key fails startup so a misconfigured installation never registers a tool that cannot work.
 */
@ConfigurationProperties("web-search")
public record WebSearchProperties(
        boolean enabled,
        String provider,
        String apiKey,
        String baseUrl,
        Duration requestTimeout,
        int maxResults,
        int maxContentChars,
        String searchDepth) {

    public static final int MAX_RESULTS_CEILING = 10;

    public WebSearchProperties {
        provider = provider == null ? "" : provider.strip();
        apiKey = apiKey == null ? "" : apiKey.strip();
        baseUrl = baseUrl == null ? "" : baseUrl.strip();
        searchDepth = searchDepth == null ? "" : searchDepth.strip();
        if (enabled) {
            if (!"tavily".equals(provider)) {
                throw new IllegalArgumentException(
                        "web-search.provider must be tavily when web search is enabled");
            }
            if (apiKey.isBlank()) {
                throw new IllegalArgumentException(
                        "web-search.api-key is required when web search is enabled");
            }
            if (!baseUrl.startsWith("https://") || baseUrl.endsWith("/")) {
                throw new IllegalArgumentException(
                        "web-search.base-url must be an https origin without a trailing slash");
            }
            if (requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()
                    || requestTimeout.compareTo(Duration.ofMinutes(2)) > 0) {
                throw new IllegalArgumentException("web-search.request-timeout must be between 1s and 2m");
            }
            if (maxResults < 1 || maxResults > MAX_RESULTS_CEILING) {
                throw new IllegalArgumentException("web-search.max-results must be between 1 and 10");
            }
            if (maxContentChars < 200 || maxContentChars > 8000) {
                throw new IllegalArgumentException(
                        "web-search.max-content-chars must be between 200 and 8000");
            }
            if (!"basic".equals(searchDepth) && !"advanced".equals(searchDepth)) {
                throw new IllegalArgumentException("web-search.search-depth must be basic or advanced");
            }
        }
    }
}
