package net.velocity.agent;

import java.util.List;
import java.util.Map;

import net.velocity.inference.objects.InferenceMessage;

/**
 * The outcome of sizing one prepared model request: the exact messages to send, the counted estimate, the
 * sizing policy's verdict, the diagnostics saved with the turn, and the snapshot revision the request was
 * sized against (a later revision means the boundary moved and the turn must be re-prepared).
 */
record ContextDecision(
        List<InferenceMessage> messages,
        ContextSizingPolicy.Estimate estimate,
        ContextSizingPolicy.Decision outcome,
        Map<String, Object> diagnostics,
        long expectedRevision) {

    boolean accepted() {
        return outcome == ContextSizingPolicy.Decision.accepted;
    }
}
