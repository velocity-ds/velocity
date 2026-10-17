package net.velocity.agent.codeexecution;

import java.time.Instant;

/** Immutable task-scoped binary content. Provenance is server supplied. */
public record CodeArtifact(
        String artifactId,
        String ownerAccountId,
        String executionId,
        String runId,
        String acceptedCallId,
        String acceptedCallBinding,
        long goalGeneration,
        String filename,
        String mediaType,
        long byteSize,
        String checksum,
        Instant createdAt) {

    public CodeArtifact {
        if (artifactId == null || !artifactId.matches("ca_[a-f0-9]{64}") || ownerAccountId == null
                || !java.util.UUID.fromString(ownerAccountId).toString().equals(ownerAccountId)
                || executionId == null || executionId.isBlank() || runId == null || runId.isBlank()
                || acceptedCallId == null || acceptedCallId.isBlank() || acceptedCallBinding == null
                || acceptedCallBinding.isBlank()
                || goalGeneration < 1 || mediaType == null || mediaType.isBlank() || byteSize < 0
                || byteSize > 10L * 1024 * 1024
                || checksum == null || !checksum.matches("[a-f0-9]{64}") || createdAt == null) {
            throw new IllegalArgumentException("Invalid code artifact metadata");
        }
        CodeFiles.filename(filename);
    }
}
