package net.velocity.inference.openai;

import java.util.Set;

import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.ComputerTool;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig;
import com.openai.models.responses.ResponseIncludable;
import com.openai.models.responses.ResponseTextConfig;
import com.openai.models.responses.WebSearchTool;

import net.velocity.inference.objects.InferenceCapability;
import net.velocity.inference.objects.InferenceRequest;
import net.velocity.inference.responses.ResponsesInferenceProvider;

/** Configures the Responses adapter for OpenAI's hosted API. */
public class OpenAIInferenceProvider extends ResponsesInferenceProvider {

    private static final Set<InferenceCapability> CAPABILITIES = Set.of(
            InferenceCapability.TEXT,
            InferenceCapability.STREAMING,
            InferenceCapability.VISION,
            InferenceCapability.DOCUMENT,
            InferenceCapability.STRUCTURED_OUTPUT,
            InferenceCapability.WEB_SEARCH,
            InferenceCapability.ADAPTIVE_THINKING,
            InferenceCapability.COMPUTER_USE);

    public OpenAIInferenceProvider(OpenAIClient client, OpenAIProperties properties) {
        super(client, properties.model(), properties.maxTokens());
    }

    @Override
    public String providerId() {
        return "openai";
    }

    @Override
    public Set<InferenceCapability> capabilities() {
        return CAPABILITIES;
    }

    @Override
    protected void configureRequest(ResponseCreateParams.Builder params, InferenceRequest request) {
        if (request.options().structuredOutput()) {
            var schema = ResponseFormatTextJsonSchemaConfig.Schema.builder();
            request.outputSchema()
                    .forEach((key, value) -> schema.putAdditionalProperty(key, JsonValue.from(value)));
            params.text(
                    ResponseTextConfig.builder()
                            .format(
                                    ResponseFormatTextJsonSchemaConfig.builder()
                                            .name("inference_output")
                                            .schema(schema.build())
                                            .strict(true)
                                            .build())
                            .build());
        }
        params.addInclude(ResponseIncludable.REASONING_ENCRYPTED_CONTENT);
        if (request.options().webSearch()) {
            params.addTool(
                    WebSearchTool.builder()
                            .type(WebSearchTool.Type.WEB_SEARCH).build());
        }
        if (request.options().adaptiveThinking()) {
            params.reasoning(
                    Reasoning.builder()
                            .effort(ReasoningEffort.MEDIUM).summary(Reasoning.Summary.AUTO).build());
        }
        if (request.options().computerUse()) {
            params.addTool(ComputerTool.builder().build());
        }
    }
}
