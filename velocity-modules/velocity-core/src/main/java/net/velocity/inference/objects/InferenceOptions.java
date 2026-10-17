package net.velocity.inference.objects;

import java.util.List;
import java.util.Map;

/**
 * Independent API features. A nonempty schema enables structured output; a positive {@code maxOutputTokens}
 * lowers the provider's configured output maximum for this request; no option selects a task prompt.
 */
public record InferenceOptions(
        Map<String, Object> outputSchema,
        boolean webSearch,
        boolean adaptiveThinking,
        boolean computerUse,
        List<FunctionTool> tools,
        long maxOutputTokens) {

    public InferenceOptions {
        outputSchema = InferenceJson.copy(outputSchema);
        tools = tools == null ? List.of() : List.copyOf(tools);
        if (maxOutputTokens < 0) {
            throw new IllegalArgumentException("Maximum output tokens must not be negative");
        }
    }

    public InferenceOptions(
            Map<String, Object> outputSchema,
            boolean webSearch,
            boolean adaptiveThinking,
            boolean computerUse) {
        this(outputSchema, webSearch, adaptiveThinking, computerUse, List.of(), 0);
    }

    public InferenceOptions(
            Map<String, Object> outputSchema,
            boolean webSearch,
            boolean adaptiveThinking,
            boolean computerUse,
            List<FunctionTool> tools) {
        this(outputSchema, webSearch, adaptiveThinking, computerUse, tools, 0);
    }

    public record FunctionTool(String name, String description, Map<String, Object> inputSchema) {

        public FunctionTool {
            InferenceJson.requireText(name, "Tool name");
            InferenceJson.requireText(description, "Tool description");
            inputSchema = InferenceJson.copy(inputSchema);
        }
    }

    public InferenceOptions withTools(List<FunctionTool> definitions) {
        return new InferenceOptions(
                outputSchema,
                webSearch,
                adaptiveThinking,
                computerUse,
                definitions,
                maxOutputTokens);
    }

    public static InferenceOptions defaults() {
        return new InferenceOptions(Map.of(), false, false, false);
    }

    public boolean structuredOutput() {
        return !outputSchema.isEmpty();
    }

    /** Enables structured output, preserving every other option. */
    public InferenceOptions withOutputSchema(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            throw new IllegalArgumentException("Structured output requires a JSON schema");
        }
        return new InferenceOptions(schema, webSearch, adaptiveThinking, computerUse, tools, maxOutputTokens);
    }

    public InferenceOptions withWebSearch(boolean enabled) {
        return new InferenceOptions(
                outputSchema,
                enabled,
                adaptiveThinking,
                computerUse,
                tools,
                maxOutputTokens);
    }

    public InferenceOptions withAdaptiveThinking(boolean enabled) {
        return new InferenceOptions(outputSchema, webSearch, enabled, computerUse, tools, maxOutputTokens);
    }

    public InferenceOptions withComputerUse(boolean enabled) {
        return new InferenceOptions(
                outputSchema,
                webSearch,
                adaptiveThinking,
                enabled,
                tools,
                maxOutputTokens);
    }

    /** Caps this request's output below the provider's configured maximum; {@code 0} keeps that maximum. */
    public InferenceOptions withMaxOutputTokens(long tokens) {
        return new InferenceOptions(outputSchema, webSearch, adaptiveThinking, computerUse, tools, tokens);
    }

    /** The output tokens a provider with {@code configuredMaximum} may generate for this request. */
    public long outputTokens(long configuredMaximum) {
        return maxOutputTokens > 0 ? Math.min(configuredMaximum, maxOutputTokens) : configuredMaximum;
    }
}
