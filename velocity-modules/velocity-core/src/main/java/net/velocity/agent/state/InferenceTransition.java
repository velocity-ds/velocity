package net.velocity.agent.state;

import java.util.List;
import java.util.Objects;

import net.velocity.inference.objects.InferenceContent;
import net.velocity.inference.objects.InferenceMessage;

/** The exact portable request prefix committed at the one-way root provider boundary. */
public record InferenceTransition(
        String reason,
        long sourceRevision,
        int sourceMessageCount,
        long sourceHistoryGeneration,
        int sourceModelTurns,
        long sourceGoalGeneration,
        List<InferenceMessage> handoffMessages) {

    public InferenceTransition {
        if (!"input_allowance_exceeded".equals(reason) || sourceRevision < 1 || sourceMessageCount < 1
                || sourceHistoryGeneration < 1 || sourceModelTurns < 0 || sourceGoalGeneration < 1
                || handoffMessages == null || handoffMessages.isEmpty()) {
            throw new IllegalArgumentException("Invalid inference transition");
        }
        handoffMessages = List.copyOf(handoffMessages);
        if (handoffMessages.stream().anyMatch(
                message -> message == null || message.content().stream()
                        .anyMatch(
                                block -> !(block instanceof InferenceContent.Text)
                                        && !(block instanceof InferenceContent.Image)))) {
            throw new IllegalArgumentException(
                    "Handoff must contain only portable, non-dispatchable content");
        }
        handoffMessages.forEach(Objects::requireNonNull);
    }
}
