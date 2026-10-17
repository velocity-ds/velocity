package net.velocity.websearch;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import net.velocity.agent.ToolRegistry;
import net.velocity.agent.spi.ContextContributor;
import net.velocity.agent.spi.SystemStatusContributor;
import net.velocity.agent.spi.ToolGuidanceContributor;
import net.velocity.agent.spi.ToolProvider;

/**
 * Always-loaded configuration of the web search feature. The client exists only while the feature is enabled;
 * the tool provider and guidance are inert otherwise, and the status section reports {@code enabled: false}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebSearchProperties.class)
public class WebSearchConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "web-search", name = "enabled", havingValue = "true")
    TavilyClient tavilyClient(WebSearchProperties properties) {
        return new TavilyClient(properties);
    }

    /** Order 1200 follows the example module (1100); see docs/agent-gateway.md. */
    @Bean
    @Order(1200)
    ToolProvider webSearchToolProvider(WebSearchProperties properties, ObjectProvider<TavilyClient> client) {
        return registry -> {
            TavilyClient tavily = client.getIfAvailable();
            if (properties.enabled() && tavily != null) {
                registry.register(WebSearchTools.search(tavily, properties));
            }
        };
    }

    @Bean
    @Order(1200)
    ContextContributor webSearchGuidance(ToolRegistry tools) {
        return ToolGuidanceContributor
                .whenRegistered(tools, WebSearchPrompts.SEARCH_INSTRUCTIONS, WebSearchTools.SEARCH);
    }

    @Bean
    @Order(1600)
    SystemStatusContributor webSearchStatus(
            WebSearchProperties properties,
            ObjectProvider<TavilyClient> client) {
        return SystemStatusContributor.of("webSearch", () -> {
            var section = new LinkedHashMap<String, Object>();
            section.put("enabled", properties.enabled());
            section.put("provider", properties.enabled() ? properties.provider() : "");
            TavilyClient tavily = client.getIfAvailable();
            section.put(
                    "configured",
                    tavily != null && Boolean.TRUE.equals(tavily.status().get("configured")));
            section.put("maxResults", properties.enabled() ? properties.maxResults() : 0);
            return Map.copyOf(section);
        });
    }
}
