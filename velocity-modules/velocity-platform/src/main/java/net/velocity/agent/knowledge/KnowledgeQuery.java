package net.velocity.agent.knowledge;

import java.util.List;

/** Validated, deliberately small query language; a null dataset is a catalog scan. */
public record KnowledgeQuery(
        String dataset,
        List<Filter> filters,
        String sortField,
        boolean descending,
        int offset,
        int limit,
        String search) {

    public KnowledgeQuery {
        filters = filters == null ? List.of() : List.copyOf(filters);
    }

    public record Filter(String field, String operator, Object value) {}
}
