package net.velocity.inference.objects;

import java.util.List;
import java.util.Objects;

/**
 * A provider-normalized text view alongside the content used for conversation replay.
 * Adapters supply text independently so preserving opaque metadata does not hide readable output.
 */
public record InferenceResponse(
        String id,
        String model,
        List<InferenceContent> content,
        String stopReason,
        Usage usage,
        String text) {

    public InferenceResponse {
        content = List.copyOf(content);
        Objects.requireNonNull(text, "text");
    }

    /**
     * Normalized provider totals: input includes cache reads and writes; output includes reasoning.
     * Cache duration fields partition cacheCreationTokens, and never add to the aggregate again.
     * Unknown nonzero categories are retained by name so token-only estimates remain visibly partial.
     */
    public record Usage(
            long inputTokens,
            long outputTokens,
            long totalTokens,
            long cacheReadTokens,
            long cacheCreationTokens,
            long cacheCreation5mTokens,
            long cacheCreation1hTokens,
            long reasoningTokens,
            boolean incomplete,
            List<String> unsupportedCategories) {

        public Usage(long inputTokens, long outputTokens) {
            this(inputTokens, outputTokens, Math.addExact(inputTokens, outputTokens));
        }

        public Usage(long inputTokens, long outputTokens, long totalTokens) {
            this(inputTokens, outputTokens, totalTokens, 0, 0, 0, 0, 0, false, List.of());
        }

        public Usage {
            if (inputTokens < 0 || outputTokens < 0 || totalTokens < 0 || cacheReadTokens < 0
                    || cacheCreationTokens < 0 || cacheCreation5mTokens < 0
                    || cacheCreation1hTokens < 0 || reasoningTokens < 0) {
                throw new IllegalArgumentException("Token usage must not be negative");
            }
            // Subtraction avoids overflow while checking provider totals. Preserve inconsistent
            // reports as incomplete evidence instead of silently inventing corrected usage.
            incomplete |= inputTokens > totalTokens || outputTokens != totalTokens - inputTokens
                    || cacheReadTokens > inputTokens || cacheCreationTokens > inputTokens - cacheReadTokens
                    || cacheCreation5mTokens > cacheCreationTokens
                    || cacheCreation1hTokens > cacheCreationTokens - cacheCreation5mTokens
                    || reasoningTokens > outputTokens;
            unsupportedCategories = unsupportedCategories == null
                    ? List.of()
                    : List.copyOf(unsupportedCategories);
        }

        public Usage withIncomplete(boolean missing) {
            return new Usage(
                    inputTokens,
                    outputTokens,
                    totalTokens,
                    cacheReadTokens,
                    cacheCreationTokens,
                    cacheCreation5mTokens,
                    cacheCreation1hTokens,
                    reasoningTokens,
                    incomplete || missing,
                    unsupportedCategories);
        }
    }

    public InferenceMessage toMessage() {
        return new InferenceMessage(InferenceMessage.Role.ASSISTANT, content);
    }
}
