package net.velocity.inference.llamacpp;

import java.util.Set;

import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.ResponseCreateParams;

import net.velocity.inference.objects.InferenceCapability;
import net.velocity.inference.objects.InferenceContent;
import net.velocity.inference.objects.InferenceMessage;
import net.velocity.inference.objects.InferenceRequest;
import net.velocity.inference.responses.ResponsesInferenceProvider;

/** Connects to llama-server's OpenAI-compatible Responses endpoint. */
public class LlamaCppInferenceProvider extends ResponsesInferenceProvider {

    private static final Set<InferenceCapability> CAPABILITIES = Set.of(
            InferenceCapability.TEXT,
            InferenceCapability.STREAMING,
            InferenceCapability.VISION,
            InferenceCapability.STRUCTURED_OUTPUT,
            InferenceCapability.ADAPTIVE_THINKING);

    public LlamaCppInferenceProvider(OpenAIClient client, LlamaCppProperties properties) {
        super(client, properties.model(), properties.maxTokens());
    }

    @Override
    public String providerId() {
        return "llama-cpp";
    }

    @Override
    public Set<InferenceCapability> capabilities() {
        return CAPABILITIES;
    }

    @Override
    protected void configureRequest(ResponseCreateParams.Builder params, InferenceRequest request) {
        for (InferenceMessage message : request.messages()) {
            for (InferenceContent content : message.content()) {
                if (content instanceof InferenceContent.Image
                        && message.role() == InferenceMessage.Role.ASSISTANT) {
                    throw new UnsupportedOperationException("LlamaCpp supports images only in user messages");
                }
                if (content instanceof InferenceContent.ToolResult result
                        && (result.toolset() != null
                                || result.content().stream()
                                        .anyMatch(part -> !(part instanceof InferenceContent.Text)))) {
                    throw new UnsupportedOperationException("LlamaCpp function results support only text");
                }
            }
        }
        if (request.options().structuredOutput()) {
            // llama-server's Responses-to-chat converter does not translate text.format yet.
            params.putAdditionalBodyProperty("json_schema", JsonValue.from(request.outputSchema()));
        }
        if (request.options().adaptiveThinking()) {
            params.reasoning(Reasoning.builder().effort(ReasoningEffort.MEDIUM).build());
        }
    }
}
