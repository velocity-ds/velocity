package net.velocity.agent.knowledge;

import java.util.List;
import java.util.Set;

/**
 * A validated read-only summary over the records of one dataset that a filter matches. {@code field} is a
 * {@code content.<path>} and is required for every operation except {@code count}; a null {@code groupBy}
 * yields a single group.
 */
public record KnowledgeAggregation(
        String dataset,
        List<KnowledgeQuery.Filter> filters,
        String operation,
        String field,
        String groupBy,
        int groupLimit) {

    public static final Set<String> OPERATIONS = Set.of("count", "sum", "avg", "min", "max");
    public static final int MAX_GROUPS = 100;

    public KnowledgeAggregation {
        filters = filters == null ? List.of() : List.copyOf(filters);
    }

    /**
     * One group's share. {@code key} is the grouping value (null without {@code groupBy} or when the field is
     * absent); {@code value} is the count for {@code count}, otherwise the numeric result, or null when no
     * matched record held a number in the field.
     */
    public record Group(Object key, long count, Object value, long skippedNonNumeric) {}

    /**
     * {@code groups} holds at most the group limit, sorted by value then key; {@code count} and
     * {@code skippedNonNumeric} cover every matched record, and {@code limitReached} says more groups existed.
     */
    public record Result(List<Group> groups, long count, long skippedNonNumeric, boolean limitReached) {

        public Result {
            groups = List.copyOf(groups);
        }
    }
}
