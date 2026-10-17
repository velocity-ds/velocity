package net.velocity.agent.scheduling;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("agent.scheduling")
public record SchedulingProperties(
        boolean enabled,
        Duration maximumLateness) {

    public SchedulingProperties {
        if (maximumLateness == null || maximumLateness.isNegative()) {
            throw new IllegalArgumentException("Recurring maximum lateness must be nonnegative");
        }
    }
}
