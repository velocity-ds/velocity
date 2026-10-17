package net.velocity.inference.claude;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CancellationException;

import org.springframework.util.Assert;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.ClientOptions;
import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.core.Timeout;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ComputerToolset20260801;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.MessageCountTokensParams;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.WebSearchTool20250305;
import com.fasterxml.jackson.databind.node.ObjectNode;

import net.velocity.inference.InferenceDeadlineScope;
import net.velocity.inference.InferenceProvider;
import net.velocity.inference.InferenceStream;
import net.velocity.inference.InputTokenCountException;
import net.velocity.inference.internal.ManagedInferenceStream;
import net.velocity.inference.internal.ProviderFailures;
import net.velocity.inference.objects.InferenceCapability;
import net.velocity.inference.objects.InferenceRequest;
import net.velocity.inference.objects.InferenceResponse;

/** Translates the common inference contract to Anthropic's Messages API. */
public class ClaudeInferenceProvider implements InferenceProvider {

    private static final Set<InferenceCapability> CAPABILITIES = Set.of(
            InferenceCapability.TEXT,
            InferenceCapability.STREAMING,
            InferenceCapability.VISION,
            InferenceCapability.DOCUMENT,
            InferenceCapability.STRUCTURED_OUTPUT,
            InferenceCapability.WEB_SEARCH,
            InferenceCapability.ADAPTIVE_THINKING,
            InferenceCapability.COMPUTER_USE);

    private final AnthropicClient client;
    private final String model;
    private final long maxTokens;
    private final boolean promptCaching;

    @Override
    public long outputTokenAllowance() {
        return maxTokens;
    }

    @Override
    public String configuredModel() {
        return model;
    }

    public ClaudeInferenceProvider(AnthropicClient client, ClaudeProperties properties) {
        Assert.notNull(client, "Anthropic client must not be null");
        Assert.notNull(properties, "Claude properties must not be null");
        this.client = client;
        this.model = properties.model();
        this.maxTokens = properties.maxTokens();
        this.promptCaching = properties.promptCaching();
    }

    @Override
    public String providerId() {
        return "claude";
    }

    @Override
    public Set<InferenceCapability> capabilities() {
        return CAPABILITIES;
    }

    @Override
    public InferenceResponse infer(InferenceRequest request) {
        requireCapabilities(request, false);
        // One durable accounting intent represents one provider dispatch. SDK retries could
        // otherwise repeat an uncertain billable request without a new accounting identity.
        // Transient failures are classified instead, so the caller can retry with a new identity.
        try {
            return ClaudeMapper.response(
                    client.messages().withOptions(options -> {
                        options.maxRetries(0);
                        applyTimeout(options, null);
                    })
                            .create(messageParams(request)));
        } catch (AnthropicServiceException failure) {
            if (ProviderFailures.retryableStatus(failure.statusCode())) {
                throw ProviderFailures.status(failure.statusCode(), failure.headers().values("retry-after"));
            }
            throw failure;
        } catch (AnthropicIoException failure) {
            if (ProviderFailures.transientConnection(failure)) {
                throw ProviderFailures.connection();
            }
            throw failure;
        }
    }

    @Override
    public OptionalLong countInputTokens(InferenceRequest request) {
        requireCapabilities(request, false);
        // The tokenizer receives exactly the same mapped prompt and tool definitions as generation.
        ObjectNode body = ObjectMappers.jsonMapper().valueToTree(messageParams(request)._body());
        body.remove("max_tokens");
        var params = MessageCountTokensParams.builder().body(
                ObjectMappers.jsonMapper()
                        .convertValue(body, MessageCountTokensParams.Body.class))
                .build();
        try {
            long count = client.messages()
                    .withOptions(options -> {
                        options.maxRetries(0);
                        applyTimeout(options, Duration.ofSeconds(10));
                    })
                    .countTokens(params).inputTokens();
            if (count < 0) {
                throw new IllegalStateException();
            }
            return OptionalLong.of(count);
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (AnthropicServiceException failure) {
            // Provider error bodies may echo prompts or credentials. Never expose or retain them.
            throw new InputTokenCountException(ProviderFailures.retryableStatus(failure.statusCode()));
        } catch (AnthropicIoException failure) {
            throw new InputTokenCountException(ProviderFailures.transientConnection(failure));
        } catch (RuntimeException failure) {
            throw new InputTokenCountException();
        }
    }

    /** The caller must close the returned stream, including when stopping consumption early. */
    @Override
    public InferenceStream stream(InferenceRequest request) {
        requireCapabilities(request, true);
        var response = client.messages().withOptions(options -> {
            options.maxRetries(0);
            applyTimeout(options, null);
        })
                .createStreaming(messageParams(request));
        var accumulator = MessageAccumulator.create();
        var usage = new ClaudeMapper.StreamUsage();
        return new ManagedInferenceStream<>("Claude", response::stream, raw -> {
            // The SDK rejects unknown top-level events; preserve them in metadata instead.
            if (raw.isMessageStart() || raw.isContentBlockStart() || raw.isContentBlockDelta()
                    || raw.isContentBlockStop() || raw.isMessageDelta() || raw.isMessageStop()) {
                accumulator.accumulate(raw);
            }
            return ClaudeMapper.event(raw, accumulator, usage);
        }, response::close);
    }

    private static void applyTimeout(ClientOptions.Builder options, Duration standaloneTimeout) {
        var remaining = InferenceDeadlineScope.remaining();
        if (remaining.isPresent()) {
            Duration timeout = remaining.orElseThrow();
            options.timeout(
                    Timeout.builder().connect(timeout).read(timeout).write(timeout).request(timeout).build());
        } else if (standaloneTimeout != null) {
            options.timeout(standaloneTimeout);
        }
    }

    /**
     * Builds the wire request for generation, streaming and token counting. With prompt caching, three
     * ephemeral cache breakpoints mark the stable prefix of an agent loop: the system prompt, the last tool
     * definition (tools precede the system prompt in the cached prefix order) and the last block of the latest
     * message, so the next turn reads everything up to there from the cache. The same breakpoints go to the
     * token counter, which accepts them. Persisted messages are never changed; the marker exists only in the
     * outgoing request. An output schema goes on the wire without the constraint keywords the API rejects
     * ({@link ClaudeOutputSchema}); the request's own schema stays intact for the caller's validation.
     */
    private MessageCreateParams messageParams(InferenceRequest request) {
        var messages = request.messages();
        var mapped = new java.util.ArrayList<com.anthropic.models.messages.MessageParam>(messages.size());
        for (int index = 0; index < messages.size(); index++) {
            mapped.add(
                    ClaudeMapper.message(messages.get(index), promptCaching && index == messages.size() - 1));
        }
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(request.options().outputTokens(maxTokens))
                .messages(mapped);
        if (request.systemPrompt() != null) {
            TextBlockParam.Builder system = TextBlockParam.builder().text(request.systemPrompt());
            if (promptCaching) {
                system.cacheControl(CacheControlEphemeral.builder().build());
            }
            params.systemOfTextBlockParams(List.of(system.build()));
        }
        if (request.options().structuredOutput()) {
            JsonOutputFormat.Schema.Builder schema = JsonOutputFormat.Schema.builder();
            // The API rejects size, range and pattern constraints; callers validate the original schema.
            ClaudeOutputSchema.sanitize(request.outputSchema())
                    .forEach((key, value) -> schema.putAdditionalProperty(key, JsonValue.from(value)));
            params.outputConfig(OutputConfig.builder().format(JsonOutputFormat.of(schema.build())).build());
        }
        if (request.options().webSearch()) {
            params.addTool(WebSearchTool20250305.builder().build());
        }
        if (request.options().adaptiveThinking()) {
            params.thinking(ThinkingConfigAdaptive.builder().build());
        }
        if (request.options().computerUse()) {
            params.addTool(ComputerToolset20260801.builder().build());
        }
        var tools = request.options().tools();
        for (int index = 0; index < tools.size(); index++) {
            var tool = tools.get(index);
            var wire = new java.util.LinkedHashMap<String, Object>();
            wire.put("name", tool.name());
            wire.put("description", tool.description());
            wire.put("input_schema", tool.inputSchema());
            if (promptCaching && index == tools.size() - 1) {
                wire.put("cache_control", ClaudeMapper.CACHE_CONTROL);
            }
            params.addTool(ObjectMappers.jsonMapper().convertValue(wire, Tool.class));
        }
        return params.build();
    }

}
