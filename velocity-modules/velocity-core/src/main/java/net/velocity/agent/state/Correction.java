package net.velocity.agent.state;

import java.time.Instant;
import java.util.List;

public record Correction(
        String id,
        String text,
        Instant createdAt,
        SourceTurn sourceTurn,
        List<SourceMessage> sourceMessages) {

    public Correction {
        sourceMessages = sourceMessages == null ? List.of() : List.copyOf(sourceMessages);
    }
}
