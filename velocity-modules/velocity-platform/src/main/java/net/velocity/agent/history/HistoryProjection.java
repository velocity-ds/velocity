package net.velocity.agent.history;

import java.util.List;

/** A rebuildable lexical projection. Execution snapshots remain authoritative. */
public interface HistoryProjection {

    record Message(
            String ownerAccountId,
            String executionId,
            String messageId,
            int sequence,
            long generation,
            String text,
            boolean truncated) {}

    long generation(String executionId);

    void replace(String executionId, long generation, List<Message> messages);

    List<Message> search(String ownerAccountId, String query, int offset, int limit);

    void delete(String executionId);
}
