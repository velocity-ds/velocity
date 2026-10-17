package net.velocity.agent.state;

import java.util.List;

import net.velocity.inference.objects.InferenceMessage;

/** Committed request-only projection. Canonical history and receipts are never rewritten. */
public record CompactRequestView(
        long sourceRevision,
        int sourceMessageCount,
        long sourceHistoryGeneration,
        long goalGeneration,
        InferenceBinding binding,
        List<InferenceMessage> messages,
        String evidenceArtifact) {

    public CompactRequestView {
        if (sourceRevision < 1 || sourceMessageCount < 1 || sourceHistoryGeneration < 0 || goalGeneration < 1
                || messages == null || messages.isEmpty() || evidenceArtifact == null
                || evidenceArtifact.isBlank()) {
            throw new IllegalArgumentException("Invalid compact request view");
        }
        messages = List.copyOf(messages);
    }
}
