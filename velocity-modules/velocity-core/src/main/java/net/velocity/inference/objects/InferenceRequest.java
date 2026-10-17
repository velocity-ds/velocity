package net.velocity.inference.objects;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A provider-independent request with separate task instructions and composable API options. */
public record InferenceRequest(
        List<InferenceMessage> messages,
        String systemPrompt,
        InferenceOptions options) {

    public InferenceRequest {
        messages = InferenceJson.content(messages, "Messages");
        if (options == null) {
            throw new IllegalArgumentException("Inference options must not be null");
        }
        if (systemPrompt != null) {
            InferenceJson.requireText(systemPrompt, "System prompt");
        }
    }

    public static InferenceRequest text(String input) {
        return new InferenceRequest(List.of(InferenceMessage.user(input)), null, InferenceOptions.defaults());
    }

    public static InferenceRequest structuredOutput(String input, Map<String, Object> schema) {
        return text(input).withOutputSchema(schema);
    }

    public static InferenceRequest webSearch(String input) {
        return text(input).withWebSearch();
    }

    public static InferenceRequest adaptiveThinking(String input) {
        return text(input).withAdaptiveThinking();
    }

    public static InferenceRequest computerUse(String input, InferenceContent.Image screenshot) {
        InferenceJson.requireText(input, "Inference input");
        if (screenshot == null) {
            throw new IllegalArgumentException("Screenshot must not be null");
        }
        return computerUse(
                List.of(
                        new InferenceMessage(
                                InferenceMessage.Role.USER,
                                List.of(screenshot, new InferenceContent.Text(input)))));
    }

    public static InferenceRequest computerUse(List<InferenceMessage> history) {
        return new InferenceRequest(history, null, InferenceOptions.defaults().withComputerUse(true));
    }

    /** Replaces feature options without changing the messages or prompt. */
    public InferenceRequest withOptions(InferenceOptions options) {
        return new InferenceRequest(messages, systemPrompt, options);
    }

    public InferenceRequest withOutputSchema(Map<String, Object> schema) {
        return withOptions(options.withOutputSchema(schema));
    }

    public InferenceRequest withWebSearch() {
        return withOptions(options.withWebSearch(true));
    }

    public InferenceRequest withAdaptiveThinking() {
        return withOptions(options.withAdaptiveThinking(true));
    }

    public InferenceRequest withComputerUse() {
        return withOptions(options.withComputerUse(true));
    }

    public Map<String, Object> outputSchema() {
        return options.outputSchema();
    }

    /** Replaces the system prompt with a preset; it does not enable any API features. */
    public InferenceRequest withPromptPreset(PromptPreset preset) {
        if (preset == null) {
            throw new IllegalArgumentException("Prompt preset must not be null");
        }
        return withSystemPrompt(preset.prompt());
    }

    public InferenceRequest withSystemPrompt(String prompt) {
        InferenceJson.requireText(prompt, "System prompt");
        return new InferenceRequest(messages, prompt, options);
    }

    public Set<InferenceCapability> requiredCapabilities() {
        var required = EnumSet.of(InferenceCapability.TEXT);
        if (options.structuredOutput()) {
            required.add(InferenceCapability.STRUCTURED_OUTPUT);
        }
        if (options.webSearch()) {
            required.add(InferenceCapability.WEB_SEARCH);
        }
        if (options.adaptiveThinking()) {
            required.add(InferenceCapability.ADAPTIVE_THINKING);
        }
        if (options.computerUse()) {
            required.add(InferenceCapability.COMPUTER_USE);
        }
        if (messages.stream().flatMap(message -> message.content().stream())
                .anyMatch(InferenceRequest::hasImage)) {
            required.add(InferenceCapability.VISION);
        }
        if (messages.stream().flatMap(message -> message.content().stream())
                .anyMatch(InferenceRequest::hasDocument)) {
            required.add(InferenceCapability.DOCUMENT);
        }
        return Set.copyOf(required);
    }

    private static boolean hasImage(InferenceContent content) {
        return content instanceof InferenceContent.Image
                || content instanceof InferenceContent.ToolResult result
                        && result.content().stream().anyMatch(InferenceRequest::hasImage);
    }

    private static boolean hasDocument(InferenceContent content) {
        return content instanceof InferenceContent.Document
                || content instanceof InferenceContent.ToolResult result
                        && result.content().stream().anyMatch(InferenceRequest::hasDocument);
    }
}
