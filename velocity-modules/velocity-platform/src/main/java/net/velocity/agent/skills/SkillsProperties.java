package net.velocity.agent.skills;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("agent.skills")
public record SkillsProperties(
        boolean enabled,
        int maximumCoreChars,
        int maximumIndexChars,
        int maximumReferenceChars,
        int maximumSkills,
        int retainedVersions,
        @DefaultValue("true") boolean selfCheckEnabled) {

    public SkillsProperties {
        if (maximumCoreChars < 100 || maximumIndexChars < 100 || maximumReferenceChars < 100
                || maximumSkills < 1 || retainedVersions < 2) {
            throw new IllegalArgumentException("Invalid bounded skill configuration");
        }
    }
}
