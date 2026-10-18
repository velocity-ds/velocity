package net.velocity.websearch;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.velocity.agent.JsonSchema;
import net.velocity.agent.ToolRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebSearchToolsTest {

    private static final WebSearchProperties ENABLED = new WebSearchProperties(
            true,
            "tavily",
            "tvly-test-key-not-real",
            "https://api.tavily.com",
            Duration.ofSeconds(10),
            5,
            2_000,
            "basic");

    @Test
    void schemasUseTheBoundedDialect() {
        assertThatCode(() -> JsonSchema.check(WebSearchTools.inputSchema())).doesNotThrowAnyException();
        assertThatCode(() -> JsonSchema.check(WebSearchTools.outputSchema())).doesNotThrowAnyException();
    }

    @Test
    void inputSchemaBoundsEveryArgument() {
        Map<String, Object> schema = WebSearchTools.inputSchema();
        assertThatCode(() -> JsonSchema.validate(schema, Map.of("query", "velocity agent")))
                .doesNotThrowAnyException();
        assertThatCode(
                () -> JsonSchema.validate(
                        schema,
                        Map.of(
                                "query",
                                "q",
                                "max_results",
                                10,
                                "topic",
                                "news",
                                "time_range",
                                "week",
                                "include_domains",
                                List.of("example.com"))))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> JsonSchema.validate(schema, Map.of())).hasMessage("Missing tool property");
        assertThatThrownBy(() -> JsonSchema.validate(schema, Map.of("query", "")))
                .hasMessage("Tool value outside bounds");
        assertThatThrownBy(() -> JsonSchema.validate(schema, Map.of("query", "q".repeat(401))))
                .hasMessage("Tool value outside bounds");
        assertThatThrownBy(() -> JsonSchema.validate(schema, Map.of("query", "q", "max_results", 11)))
                .hasMessage("Tool value outside bounds");
        assertThatThrownBy(() -> JsonSchema.validate(schema, Map.of("query", "q", "topic", "sports")))
                .hasMessage("Tool value outside enum");
        assertThatThrownBy(() -> JsonSchema.validate(schema, Map.of("query", "q", "page", 2)))
                .hasMessage("Unexpected tool property");
    }

    @Test
    void theToolIsATrustedCacheableNetworkReadAvailableToMonitoring() {
        ToolRegistry.Definition definition = WebSearchTools.search(null, ENABLED);
        assertThat(definition.name()).isEqualTo(WebSearchTools.SEARCH).isEqualTo("web_search");
        assertThat(definition.category()).isEqualTo("network.read");
        assertThat(definition.trusted()).isTrue();
        assertThat(definition.scoped()).isFalse();
        assertThat(definition.cacheable()).isTrue();
        assertThat(definition.timeoutMillis()).isEqualTo(15_000);
        assertThat(definition.monitoringAccess()).isEqualTo(ToolRegistry.MonitoringAccess.observation);
        assertThat(definition.description()).isEqualTo(WebSearchPrompts.SEARCH_DESCRIPTION);
        assertThat(definition.inputSchema()).isEqualTo(WebSearchTools.inputSchema());
    }

    @Test
    void enabledPropertiesAreValidatedAndDisabledOnesAreNot() {
        assertThatCode(() -> new WebSearchProperties(false, null, null, null, null, 0, 0, null))
                .doesNotThrowAnyException();
        assertThat(new WebSearchProperties(false, " x ", null, null, null, 0, 0, null).provider())
                .isEqualTo("x");
        assertThatThrownBy(
                () -> new WebSearchProperties(
                        true,
                        "bing",
                        "k",
                        "https://x",
                        Duration.ofSeconds(1),
                        1,
                        200,
                        "basic"))
                .hasMessageContaining("provider must be tavily");
        assertThatThrownBy(
                () -> new WebSearchProperties(
                        true,
                        "tavily",
                        " ",
                        "https://x",
                        Duration.ofSeconds(1),
                        1,
                        200,
                        "basic"))
                .hasMessageContaining("api-key is required");
        assertThatThrownBy(
                () -> new WebSearchProperties(
                        true,
                        "tavily",
                        "k",
                        "http://x",
                        Duration.ofSeconds(1),
                        1,
                        200,
                        "basic"))
                .hasMessageContaining("base-url");
        assertThatThrownBy(
                () -> new WebSearchProperties(
                        true,
                        "tavily",
                        "k",
                        "https://x/",
                        Duration.ofSeconds(1),
                        1,
                        200,
                        "basic"))
                .hasMessageContaining("base-url");
        assertThatThrownBy(
                () -> new WebSearchProperties(
                        true,
                        "tavily",
                        "k",
                        "https://x",
                        Duration.ofMinutes(3),
                        1,
                        200,
                        "basic"))
                .hasMessageContaining("request-timeout");
        assertThatThrownBy(
                () -> new WebSearchProperties(
                        true,
                        "tavily",
                        "k",
                        "https://x",
                        Duration.ofSeconds(1),
                        11,
                        200,
                        "basic"))
                .hasMessageContaining("max-results");
        assertThatThrownBy(
                () -> new WebSearchProperties(
                        true,
                        "tavily",
                        "k",
                        "https://x",
                        Duration.ofSeconds(1),
                        1,
                        100,
                        "basic"))
                .hasMessageContaining("max-content-chars");
        assertThatThrownBy(
                () -> new WebSearchProperties(
                        true,
                        "tavily",
                        "k",
                        "https://x",
                        Duration.ofSeconds(1),
                        1,
                        200,
                        "deep"))
                .hasMessageContaining("search-depth");
    }
}
