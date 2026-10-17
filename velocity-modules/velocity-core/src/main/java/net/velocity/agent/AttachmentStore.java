package net.velocity.agent;

import java.util.List;

import net.velocity.agent.state.TrustedScope;
import net.velocity.agent.state.UploadedFile;

/**
 * Durable storage for files the requester supplies with a task. The gateway stores them under the new
 * execution's ID before the snapshot is persisted and discards them when admission fails, so an admitted
 * task never references a file that was not saved.
 */
public interface AttachmentStore {

    /** Metadata the gateway places in the first user message; bytes never enter the snapshot. */
    record Stored(String id, String filename, String mediaType, long byteSize) {}

    /**
     * Validates and saves every upload, or throws {@link IllegalArgumentException} with a message the
     * requester can act on. Nothing is saved when any upload is rejected.
     */
    List<Stored> store(String executionId, TrustedScope scope, List<UploadedFile> uploads);

    /**
     * Validates and saves files a later turn adds to an owned execution, counting the task's earlier files
     * against the same limits, or throws {@link IllegalArgumentException} with a message the requester can
     * act on. Nothing is saved when validation rejects any upload; a storage failure partway leaves the
     * files already saved listed on the task.
     */
    List<Stored> attach(String executionId, TrustedScope scope, List<UploadedFile> uploads);

    /** Removes everything stored for an execution whose admission failed. */
    void discard(String executionId);

    /** The files stored for an owned execution, so a continuation can carry them into its own execution. */
    List<UploadedFile> stored(String executionId, TrustedScope scope);

    /** Rejects every upload; used when no store is installed. */
    static AttachmentStore none() {
        return new AttachmentStore() {

            @Override
            public List<Stored> store(String executionId, TrustedScope scope, List<UploadedFile> uploads) {
                if (!uploads.isEmpty()) {
                    throw new IllegalArgumentException("File uploads are not available");
                }
                return List.of();
            }

            @Override
            public List<Stored> attach(String executionId, TrustedScope scope, List<UploadedFile> uploads) {
                return store(executionId, scope, uploads);
            }

            @Override
            public void discard(String executionId) {
            }

            @Override
            public List<UploadedFile> stored(String executionId, TrustedScope scope) {
                return List.of();
            }
        };
    }
}
