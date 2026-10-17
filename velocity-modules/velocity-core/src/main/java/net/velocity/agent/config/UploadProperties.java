package net.velocity.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Limits for files the requester supplies with a task. Storage and extraction limits stay under agent.research. */
@ConfigurationProperties("agent.uploads")
public record UploadProperties(boolean enabled, int maxFiles, long maxFileBytes, long maxTotalBytes) {

    public UploadProperties {
        if (maxFiles < 1 || maxFiles > 100) {
            throw new IllegalArgumentException("Upload max-files must be between 1 and 100");
        }
        if (maxFileBytes < 1024) {
            throw new IllegalArgumentException("Upload max-file-bytes must be at least 1024");
        }
        if (maxTotalBytes < maxFileBytes) {
            throw new IllegalArgumentException("Upload max-total-bytes must be at least max-file-bytes");
        }
    }

    /** The HTTP body ceiling for a request that may carry Base64-encoded uploads beside its JSON fields. */
    public long requestBodyBytes() {
        return maxTotalBytes / 3 * 4 + 4 + 1_048_576;
    }
}
