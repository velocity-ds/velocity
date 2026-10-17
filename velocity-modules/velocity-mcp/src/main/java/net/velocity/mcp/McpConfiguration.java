package net.velocity.mcp;

import java.util.LinkedHashMap;
import java.util.List;
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
 * Always-loaded configuration of the MCP connector. The connections bean exists only while the feature is
 * enabled; the tool provider and guidance are inert otherwise, and the status section reports
 * {@code enabled: false}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(McpProperties.class)
public class McpConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "mcp", name = "enabled", havingValue = "true")
    McpConnections mcpConnections(McpProperties properties) {
        return new McpConnections(properties);
    }

    /** Order 1300 follows the web search module (1200); see docs/agent-gateway.md. */
    @Bean
    @Order(1300)
    ToolProvider mcpToolProvider(McpProperties properties, ObjectProvider<McpConnections> connections) {
        return registry -> {
            McpConnections servers = connections.getIfAvailable();
            if (properties.enabled() && servers != null) {
                servers.register(registry);
            }
        };
    }

    /** Guidance applies only when at least one MCP tool was registered; depending on the registry orders it. */
    @Bean
    @Order(1300)
    ContextContributor mcpGuidance(ToolRegistry tools, ObjectProvider<McpConnections> connections) {
        McpConnections servers = connections.getIfAvailable();
        boolean registered = servers != null && servers.registeredToolCount() > 0;
        return ToolGuidanceContributor.of(registered ? McpPrompts.INSTRUCTIONS : null);
    }

    @Bean
    @Order(1700)
    SystemStatusContributor mcpStatus(McpProperties properties, ObjectProvider<McpConnections> connections) {
        return SystemStatusContributor.of("mcp", () -> {
            var section = new LinkedHashMap<String, Object>();
            section.put("enabled", properties.enabled());
            section.put("confirmWrites", properties.enabled() && properties.confirmWrites());
            McpConnections servers = connections.getIfAvailable();
            if (servers == null) {
                section.put("servers", List.of());
                section.put("registeredTools", 0);
            } else {
                section.putAll(servers.status());
            }
            return Map.copyOf(section);
        });
    }
}
