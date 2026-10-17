package net.velocity.slack;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;

import net.velocity.agent.spi.SystemStatusContributor;

/** The Slack section of the operator status document; reported whether or not Slack is enabled. */
@Configuration(proxyBeanMethods = false)
public class SlackStatusConfiguration {

    public record SlackStatus(
            boolean enabled,
            boolean configured,
            boolean lifecycleRunning,
            String connection) {}

    @Bean
    @Order(500)
    SystemStatusContributor slackStatus(
            Environment environment,
            ObjectProvider<SlackSocketConnection> slack) {
        return SystemStatusContributor.of("slack", () -> {
            boolean slackEnabled = environment.getProperty("slack.enabled", Boolean.class, false);
            var connection = slack.getIfAvailable();
            boolean slackConfigured = nonblank(environment, "slack.bot-token")
                    && nonblank(environment, "slack.app-token");
            return new SlackStatus(
                    slackEnabled,
                    slackConfigured,
                    connection != null && connection.isRunning(),
                    slackEnabled ? "unknown" : "disabled");
        });
    }

    /**
     * Health for {@code /api/operator/actuator/health}: UNKNOWN while Slack is disabled, UP while the Socket
     * Mode lifecycle runs, DOWN when it is enabled but not running. No Slack API call is made.
     */
    @Bean
    HealthIndicator slackHealthIndicator(
            Environment environment,
            ObjectProvider<SlackSocketConnection> slack) {
        return () -> {
            boolean enabled = environment.getProperty("slack.enabled", Boolean.class, false);
            if (!enabled) {
                return Health.unknown().withDetail("enabled", false).build();
            }
            var connection = slack.getIfAvailable();
            boolean running = connection != null && connection.isRunning();
            return (running ? Health.up() : Health.down()).withDetail("enabled", true)
                    .withDetail("lifecycleRunning", running).build();
        };
    }

    private static boolean nonblank(Environment environment, String property) {
        String value = environment.getProperty(property);
        return value != null && !value.isBlank();
    }
}
