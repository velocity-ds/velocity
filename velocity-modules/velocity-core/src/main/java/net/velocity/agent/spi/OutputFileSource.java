package net.velocity.agent.spi;

import java.util.Optional;

import net.velocity.agent.ToolRegistry;

/**
 * A feature's owned task files that {@code task_attach_file} may copy into a deliverable output file. Core
 * resolves its own research artifacts; a feature contributes one bean per artifact store, collected with
 * {@code ObjectProvider.orderedStream()}.
 *
 * <p>Contract: the source must verify that the identifier belongs to the context's execution and trusted
 * scope and return the stored bytes only when they still match their recorded checksum. An unknown or
 * foreign identifier returns empty; resolution grants nothing beyond a copy of the file.
 */
@FunctionalInterface
public interface OutputFileSource {

    /** A file copied from an owned artifact; {@code bytes} are the stored content, never model text. */
    record SourceFile(String filename, String mediaType, byte[] bytes) {

        public SourceFile {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    Optional<SourceFile> find(ToolRegistry.Context context, String sourceId);
}
