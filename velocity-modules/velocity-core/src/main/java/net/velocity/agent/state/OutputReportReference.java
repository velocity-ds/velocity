package net.velocity.agent.state;

/** Frozen typed output metadata; report content remains in managed storage. */
public record OutputReportReference(
        String reportId,
        long goalGeneration,
        String filename,
        String mediaType,
        long byteSize,
        String checksum,
        String coverage) {}
