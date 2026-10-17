package net.velocity.agent.memory;

import java.util.Optional;

public interface MemoryRepository {

    Optional<MemoryStore> find(String ownerAccountId, String target);

    /** expectedRevision zero means insert if absent; never upsert over an unread document. */
    boolean compareAndSet(MemoryStore replacement, long expectedRevision);
}
