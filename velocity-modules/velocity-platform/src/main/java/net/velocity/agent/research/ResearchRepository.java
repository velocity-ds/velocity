package net.velocity.agent.research;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Immutable typed blobs. Domain result IDs are deterministic accepted-call receipts. */
public interface ResearchRepository {

    ResearchArtifact save(
            ResearchArtifact artifact,
            byte[] bytes,
            Map<String, String> privateMetadata,
            int maxArtifacts);

    Optional<ResearchArtifact> find(String id);

    byte[] bytes(String id);

    Map<String, String> privateMetadata(String id);

    List<ResearchArtifact> list(String executionId, String kind, int limit);

    /** Durable request admission: failed/uncertain fetches keep their count. */
    boolean reserveFetch(String executionId, int maxTaskRequests);

    void deleteExecution(String executionId);
}
