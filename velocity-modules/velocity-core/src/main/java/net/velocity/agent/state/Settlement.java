package net.velocity.agent.state;

import java.time.Instant;

/** Confirmation of the local result, not an exactly-once claim about an external effect. */
public record Settlement(
        String callId,
        String binding,
        boolean error,
        String outcome,
        String preview,
        String artifact,
        Instant confirmedAt) {}
