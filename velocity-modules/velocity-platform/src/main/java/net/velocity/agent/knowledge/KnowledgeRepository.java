package net.velocity.agent.knowledge;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** Every operation is scoped to the owner derived from trusted Java identity. */
public interface KnowledgeRepository {

    void insert(KnowledgeRecord record);

    boolean replace(KnowledgeRecord record, long expectedRevision);

    /** Removes one record only while it is still at the expected revision. */
    boolean delete(String ownerAccountId, String id, long expectedRevision);

    long count(String ownerAccountId, String dataset);

    Optional<KnowledgeRecord> findById(String ownerAccountId, String id);

    Optional<KnowledgeRecord> findByKey(String ownerAccountId, String dataset, String key);

    List<KnowledgeRecord> query(String ownerAccountId, KnowledgeQuery query);

    /** Summarizes the matching records of one dataset; numeric results come back as JSON numbers. */
    KnowledgeAggregation.Result aggregate(String ownerAccountId, KnowledgeAggregation aggregation);

    List<String> datasets(String ownerAccountId, String after, int limit, String search);

    /** Sorted by dataset/key; the caller must close the stream. */
    Stream<KnowledgeRecord> scan(String ownerAccountId);
}
