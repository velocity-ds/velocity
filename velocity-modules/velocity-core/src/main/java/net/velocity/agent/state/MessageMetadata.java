package net.velocity.agent.state;

import java.time.Instant;

/** Identity and provenance beside native content; unknown historical timestamps remain null. */
public record MessageMetadata(
        String messageId,
        int sequence,
        String role,
        SourceTurn source,
        Instant timestamp) {}
