package net.velocity.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import net.velocity.agent.state.ExecutionLimits;

@ConfigurationProperties("agent")
public record AgentProperties(
        boolean automaticResumption,
        @DefaultValue Limits limits) {

    public AgentProperties {
        limits.executionLimits();
    }

    /**
     * Server limits. {@code taskTokenCeiling} is the total charged tokens at which an ordinary task
     * stops as partial; {@code 0} disables it. It does not apply to monitoring or learning work.
     */
    public record Limits(
            long totalTokens,
            long activeMillis,
            int modelTurns,
            int toolCalls,
            long toolTimeoutMillis,
            int outputPreviewChars,
            long taskTokenCeiling) {

        public Limits {
            if (taskTokenCeiling < 0) {
                throw new IllegalArgumentException("agent.limits.task-token-ceiling must not be negative");
            }
        }

        /** Server limits carry no output reserve; monitoring limits bring their own. */
        public ExecutionLimits executionLimits() {
            return new ExecutionLimits(
                    totalTokens,
                    activeMillis,
                    modelTurns,
                    toolCalls,
                    toolTimeoutMillis,
                    outputPreviewChars,
                    0L);
        }
    }
}
