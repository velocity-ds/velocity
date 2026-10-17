package net.velocity.operator;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import net.velocity.agent.AgentGateway;
import net.velocity.inference.InferenceService;

/**
 * Health contributors for {@code /api/operator/actuator/health}. They report local state only: no provider,
 * Slack or network probe runs, and no credential, host or exception text is included. MongoDB's indicator is
 * auto-configured by Spring Boot and does ping the database.
 */
@Configuration(proxyBeanMethods = false)
public class HealthConfiguration {

    /** UP with the selected provider; UNKNOWN when no provider is configured, which is a valid setup. */
    @Bean
    HealthIndicator inferenceHealthIndicator(InferenceService inference) {
        return () -> {
            if (!inference.available()) {
                return Health.unknown().withDetail("configured", false).build();
            }
            return Health.up().withDetail("configured", true).withDetail("provider", inference.providerId())
                    .withDetail("capabilities", inference.capabilities().size()).build();
        };
    }

    /** UP while the gateway accepts work, with queue occupancy; DOWN once it is draining for shutdown. */
    @Bean
    HealthIndicator agentGatewayHealthIndicator(AgentGateway gateway) {
        return () -> {
            var status = gateway.concurrencyStatus();
            var health = gateway.closing() ? Health.down().withDetail("closing", true) : Health.up();
            return health.withDetail("activeRoots", status.activeRoots())
                    .withDetail("queuedRoots", status.queuedRoots())
                    .withDetail("maxActiveRoots", status.maxActiveRoots())
                    .withDetail("queueCapacity", status.queueCapacity())
                    .withDetail("inferenceAvailable", gateway.available()).build();
        };
    }
}
