package net.velocity.agent.state;

import net.velocity.inference.objects.InferenceResponse;

/** Must run inside the repository's atomic snapshot mutation. */
public final class UsageAccounting {

    private UsageAccounting() {
    }

    public static void observe(
            ConversationState snapshot,
            String source,
            InferenceResponse.Usage cumulative,
            boolean missing) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("Usage source is required");
        }
        var previous = snapshot.usageSources.computeIfAbsent(source, ignored -> new UsageCheckpoint());
        boolean nonRegressing = cumulative != null && cumulative.inputTokens() >= previous.inputTokens
                && cumulative.outputTokens() >= previous.outputTokens
                && cumulative.totalTokens() >= previous.totalTokens;
        if (cumulative != null) {
            long input = Math.max(previous.inputTokens, cumulative.inputTokens());
            long output = Math.max(previous.outputTokens, cumulative.outputTokens());
            long total = Math.max(previous.totalTokens, cumulative.totalTokens());
            snapshot.inputTokens = Math.addExact(snapshot.inputTokens, input - previous.inputTokens);
            snapshot.outputTokens = Math.addExact(snapshot.outputTokens, output - previous.outputTokens);
            snapshot.totalTokens = Math.addExact(snapshot.totalTokens, total - previous.totalTokens);
            previous.inputTokens = input;
            previous.outputTokens = output;
            previous.totalTokens = total;
        }
        if (cumulative == null || nonRegressing) {
            previous.missing = missing || cumulative == null || cumulative.incomplete();
        }
        refreshMissing(snapshot);
    }

    /** Old snapshots retain their recorded total; their unavailable input/output split stays explicit. */
    public static void refreshMissing(ConversationState snapshot) {
        long trackedTurns = snapshot.usageSources.keySet().stream().filter(key -> key.startsWith("model:"))
                .count();
        snapshot.usageMissing = snapshot.modelTurns > trackedTurns
                || snapshot.usageSources.isEmpty() && snapshot.totalTokens > 0
                || snapshot.usageSources.values().stream()
                        .anyMatch(source -> source == null || source.missing);
    }
}
