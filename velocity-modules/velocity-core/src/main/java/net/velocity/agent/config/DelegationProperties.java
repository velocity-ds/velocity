package net.velocity.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Trusted storage and schema bounds for child work sharing its parent's task deadline. */
@ConfigurationProperties("agent.delegation")
public record DelegationProperties(
        boolean enabled,
        int maxChildren,
        int maxObjectiveChars,
        int maxContextChars,
        int maxReferences,
        int maxResultBytes,
        int maxChildStateBytes,
        int maxAggregateStateBytes) {

    public DelegationProperties {
        minimum(maxChildren, 1, "max-children");
        minimum(maxObjectiveChars, 1, "max-objective-chars");
        minimum(maxContextChars, 1, "max-context-chars");
        minimum(maxReferences, 1, "max-references");
        minimum(maxResultBytes, 512, "max-result-bytes");
        minimum(maxChildStateBytes, 16_384, "max-child-state-bytes");
        minimum(maxAggregateStateBytes, maxChildStateBytes, "max-aggregate-state-bytes");
        if (maxResultBytes > maxChildStateBytes) {
            throw new IllegalArgumentException("Delegation result must fit the child state allowance");
        }
    }

    private static void minimum(long value, long minimum, String name) {
        if (value < minimum) {
            throw new IllegalArgumentException("Delegation " + name + " must be at least " + minimum);
        }
    }
}
