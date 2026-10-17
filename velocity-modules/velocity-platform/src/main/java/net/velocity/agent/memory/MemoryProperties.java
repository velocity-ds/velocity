package net.velocity.agent.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("agent.memory")
public record MemoryProperties(
        boolean enabled,
        boolean userEnabled,
        boolean environmentEnabled,
        int userMaxChars,
        int environmentMaxChars,
        int maxReceipts,
        int maxProposals,
        int maxWritesPerExecution,
        @DefaultValue("true") boolean profileEnabled,
        @DefaultValue("1500") int profileMaxChars) {

    public MemoryProperties {
        if (userMaxChars < 64 || environmentMaxChars < 64 || profileMaxChars < 64
                || maxReceipts < 1 || maxProposals < 1
                || maxWritesPerExecution < 1) {
            throw new IllegalArgumentException("Invalid memory limits");
        }
    }

    public boolean targetEnabled(String target) {
        return enabled && switch (target) {
            case "user" -> userEnabled;
            case "environment" -> environmentEnabled;
            case "profile" -> profileEnabled;
            default -> false;
        };
    }

    public int maxChars(String target) {
        return switch (target) {
            case "user" -> userMaxChars;
            case "profile" -> profileMaxChars;
            default -> environmentMaxChars;
        };
    }
}
