package net.velocity.agent.learning;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("agent.learning")
public record LearningProperties(
        boolean enabled,
        int factReviewEveryUserTurns,
        int skillReviewEveryToolIterations,
        int maximumInputChars,
        long maximumTotalTokens,
        long maximumMillis,
        int maximumModelTurns,
        int maximumToolCalls,
        boolean workerEnabled,
        long maximumOutputTokens,
        @DefaultValue("") String profile,
        @DefaultValue("6000") int maximumInteractionChars,
        @DefaultValue("3") int maximumReviewInteractions,
        @DefaultValue("2") int maximumConsecutiveFailures,
        @DefaultValue("on") String notifications,
        @DefaultValue("5") int profileReviewEveryFactReviews,
        @DefaultValue("7") int curatorEveryDays) {

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public LearningProperties {
        if (factReviewEveryUserTurns < 1 || skillReviewEveryToolIterations < 1
                || maximumInputChars < 1000 || maximumTotalTokens < 4096
                || maximumMillis < 1000 || maximumModelTurns < 1 || maximumToolCalls < 1
                || maximumOutputTokens < 1 || maximumInteractionChars < 500
                || maximumInteractionChars > maximumInputChars || maximumReviewInteractions < 1
                || maximumReviewInteractions > 20 || maximumConsecutiveFailures < 1
                || profileReviewEveryFactReviews < 1 || curatorEveryDays < 0) {
            throw new IllegalArgumentException("Invalid bounded learning configuration");
        }
        profile = profile == null ? "" : profile.strip();
        notifications = notifications == null
                ? "on"
                : notifications.strip().toLowerCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("off", "on", "verbose").contains(notifications)) {
            throw new IllegalArgumentException("agent.learning.notifications must be off, on or verbose");
        }
    }

    /** The configured review profile, or null when reviews use the source task's pinned profile. */
    public String reviewProfile() {
        return profile.isEmpty() ? null : profile;
    }
}
