package net.velocity.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Installation-wide root worker capacity; delegated work shares its root's slot. */
@ConfigurationProperties("agent.concurrency")
public record AgentConcurrencyProperties(
        int maxActiveRoots,
        int queueCapacity) {

    public AgentConcurrencyProperties {
        if (maxActiveRoots < 1 || queueCapacity < 0) {
            throw new IllegalArgumentException(
                    "Root concurrency must be positive and queue capacity nonnegative");
        }
    }
}
