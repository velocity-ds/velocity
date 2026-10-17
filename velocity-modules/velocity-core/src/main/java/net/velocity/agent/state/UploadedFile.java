package net.velocity.agent.state;

import java.util.Objects;

/**
 * A file the requester supplied with a task, as received by a trusted ingress adapter. The declared media
 * type is advisory; storage decides the recorded type from the bytes and filename.
 */
public record UploadedFile(String filename, String declaredMediaType, byte[] bytes) {

    public UploadedFile {
        Objects.requireNonNull(filename, "filename");
        Objects.requireNonNull(bytes, "bytes");
        declaredMediaType = declaredMediaType == null ? "" : declaredMediaType;
    }
}
