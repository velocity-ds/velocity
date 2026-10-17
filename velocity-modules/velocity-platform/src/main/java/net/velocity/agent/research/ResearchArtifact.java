package net.velocity.agent.research;

import java.time.Instant;
import java.util.List;

import net.velocity.agent.state.TrustedScope;

/** Immutable metadata only. Bytes and private source URLs never enter execution snapshots. */
public record ResearchArtifact(
        String id,
        String kind,
        String executionId,
        TrustedScope scope,
        long goalGeneration,
        String filename,
        String mediaType,
        long byteSize,
        String checksum,
        Instant createdAt,
        List<String> evidenceIds,
        String coverage,
        String sourceUrl,
        String title,
        String status,
        String extractionMethod) {

    public ResearchArtifact {
        scope.requireOwnerAccountId();
        evidenceIds = List.copyOf(evidenceIds);
    }
}
