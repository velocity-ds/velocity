package net.velocity.agent.history;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("agent.history")
public record HistoryProperties(
        boolean searchEnabled,
        int maximumIndexedExecutionsPerPass,
        int maximumIndexedMessageChars) {

    public HistoryProperties {
        if (maximumIndexedExecutionsPerPass < 1
                || maximumIndexedMessageChars < 1000) {
            throw new IllegalArgumentException("Invalid history projection bounds");
        }
    }
}
