package net.velocity.agent.state;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** A bounded model declaration about the user goal, not a server verification of its truth. */
public record TaskOutcome(
        Status status,
        List<String> criteria,
        List<String> evidence,
        List<String> limitations,
        String basis,
        long goalGeneration,
        Instant reportedAt) {

    public enum Status {
        completed, partial, blocked, failed
    }

    public TaskOutcome {
        Objects.requireNonNull(status);
        criteria = checked(criteria);
        evidence = checked(evidence);
        limitations = checked(limitations);
        if (!"model_report".equals(basis) || goalGeneration < 1 || reportedAt == null
                || criteria.isEmpty() || status == Status.completed && evidence.isEmpty()
                || status != Status.completed && limitations.isEmpty()) {
            throw new IllegalArgumentException("Invalid model task outcome");
        }
    }

    private static List<String> checked(List<String> values) {
        if (values == null || values.size() > 12 || values.stream()
                .anyMatch(value -> value == null || value.isBlank() || value.length() > 2_000)) {
            throw new IllegalArgumentException("Invalid model task outcome details");
        }
        return List.copyOf(values);
    }
}
