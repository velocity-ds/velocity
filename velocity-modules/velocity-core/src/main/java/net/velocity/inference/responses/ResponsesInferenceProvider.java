package net.velocity.inference.responses;

import java.io.EOFException;
import java.io.InterruptedIOException;
import java.net.SocketException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CancellationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openai.client.OpenAIClient;
import com.openai.core.ClientOptions;
import com.openai.core.ObjectMappers;
import com.openai.core.Timeout;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.inputtokens.InputTokenCountParams;

import net.velocity.inference.InferenceDeadlineScope;
import net.velocity.inference.InferenceProvider;
import net.velocity.inference.InferenceStream;
import net.velocity.inference.InputTokenCountException;
import net.velocity.inference.internal.ManagedInferenceStream;
import net.velocity.inference.internal.ProviderFailures;
import net.velocity.inference.objects.InferenceRequest;
import net.velocity.inference.objects.InferenceResponse;

/** Shared transport, message mapping, and streaming for Responses-compatible servers. */
public abstract class ResponsesInferenceProvider implements InferenceProvider {

    private static final Logger log = LoggerFactory.getLogger(ResponsesInferenceProvider.class);
    private static final Duration COUNT_TIMEOUT = Duration.ofSeconds(10);
    private final OpenAIClient client;
    private final String model;
    private final long maxTokens;

    @Override
    public long outputTokenAllowance() {
        return maxTokens;
    }

    @Override
    public String configuredModel() {
        return model;
    }

    protected ResponsesInferenceProvider(OpenAIClient client, String model, long maxTokens) {
        Assert.notNull(client, "Responses client must not be null");
        this.client = client;
        this.model = model;
        this.maxTokens = maxTokens;
    }

    @Override
    public InferenceResponse infer(InferenceRequest request) {
        requireCapabilities(request, false);
        // Disable hidden retries only for inference dispatch; each actual request needs its
        // own durable identity, especially when a transport failure leaves billing uncertain.
        // Transient failures are classified instead, so the caller can retry with a new identity.
        try {
            return ResponsesMapper.response(
                    client.responses().withOptions(options -> {
                        options.maxRetries(0);
                        applyTimeout(options, null);
                    })
                            .create(responseParams(request)),
                    providerId());
        } catch (OpenAIServiceException failure) {
            if (ProviderFailures.retryableStatus(failure.statusCode())) {
                throw ProviderFailures.status(failure.statusCode(), failure.headers().values("retry-after"));
            }
            throw failure;
        } catch (OpenAIIoException failure) {
            if (ProviderFailures.transientConnection(failure)) {
                throw ProviderFailures.connection();
            }
            throw failure;
        }
    }

    @Override
    public OptionalLong countInputTokens(InferenceRequest request) {
        requireCapabilities(request, false);
        // Reuse the actual provider mapping, including native history, tools, images and schemas.
        ObjectNode body = ObjectMappers.jsonMapper().valueToTree(responseParams(request)._body());
        body.remove(List.of("max_output_tokens", "store", "include"));
        var params = InputTokenCountParams.builder().body(
                ObjectMappers.jsonMapper()
                        .convertValue(body, InputTokenCountParams.Body.class))
                .build();
        long deadline = System.nanoTime() + InferenceDeadlineScope.remainingOr(COUNT_TIMEOUT).toNanos();
        int attempts = 0;
        try {
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new CancellationException();
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new InputTokenCountException();
                }
                attempts++;
                try {
                    long count = client.responses().inputTokens()
                            .withOptions(options -> {
                                options.maxRetries(0);
                                applyTimeout(options, Duration.ofNanos(remaining));
                            })
                            .count(params).inputTokens();
                    if (count < 0) {
                        throw new IllegalStateException();
                    }
                    return OptionalLong.of(count);
                } catch (OpenAIIoException failure) {
                    if (Thread.currentThread().isInterrupted() || interrupted(failure)) {
                        throw new CancellationException();
                    }
                    // Counting is read-only. A closed pooled socket can be retried once with
                    // the exact same payload, within the original deadline. Generation and
                    // tool dispatch never enter this loop; SDK retries remain disabled.
                    if (attempts >= 2 || !closedConnection(failure)) {
                        throw failure;
                    }
                    log.info(
                            "provider={} stage=input_token_count outcome=reconnecting attempt={}",
                            providerId(),
                            attempts);
                }
            }
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (RuntimeException failure) {
            // Provider error bodies may echo prompts or credentials. Never expose or retain them.
            log.warn(
                    "provider={} stage=input_token_count outcome=failed attempts={} exception={} httpStatus={}",
                    providerId(),
                    attempts,
                    failure.getClass().getSimpleName(),
                    failure instanceof OpenAIServiceException service ? service.statusCode() : null);
            throw new InputTokenCountException(
                    failure instanceof OpenAIServiceException service
                            ? ProviderFailures.retryableStatus(service.statusCode())
                            : failure instanceof OpenAIIoException
                                    && ProviderFailures.transientConnection(failure));
        }
    }

    private static boolean interrupted(Throwable failure) {
        for (int depth = 0; failure != null && depth < 16; depth++, failure = failure.getCause()) {
            if (failure instanceof InterruptedException || failure instanceof CancellationException) {
                return true;
            }
        }
        return false;
    }

    private static boolean closedConnection(Throwable failure) {
        boolean closed = false;
        for (int depth = 0; failure != null && depth < 16; depth++, failure = failure.getCause()) {
            // Socket timeouts also extend InterruptedIOException; neither they nor an
            // interrupted write establish a stale pooled connection.
            if (failure instanceof InterruptedIOException) {
                return false;
            }
            if (failure instanceof EOFException || failure instanceof SocketException) {
                closed = true;
            }
        }
        return closed;
    }

    /** The caller must close the returned stream, including when stopping consumption early. */
    @Override
    public InferenceStream stream(InferenceRequest request) {
        requireCapabilities(request, true);
        var response = client.responses().withOptions(options -> {
            options.maxRetries(0);
            applyTimeout(options, null);
        })
                .createStreaming(responseParams(request));
        String providerId = providerId();
        // Terminal Responses events include the full output, including tool and reasoning items.
        return new ManagedInferenceStream<>(
                providerId,
                response::stream,
                raw -> ResponsesMapper.event(raw, providerId),
                response::close);
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

    private ResponseCreateParams responseParams(InferenceRequest request) {
        var params = ResponseCreateParams.builder()
                .model(model)
                .maxOutputTokens(request.options().outputTokens(maxTokens))
                .inputOfResponse(ResponsesMapper.input(request.messages(), providerId()))
                // Conversation state belongs to the caller, not the remote server.
                .store(false);
        if (request.systemPrompt() != null) {
            params.instructions(request.systemPrompt());
        }
        request.options().tools().forEach(
                tool -> params.addTool(
                        ObjectMappers.jsonMapper().convertValue(
                                Map.of(
                                        "type",
                                        "function",
                                        "name",
                                        tool.name(),
                                        "description",
                                        tool.description(),
                                        "parameters",
                                        tool.inputSchema(),
                                        "strict",
                                        false),
                                FunctionTool.class)));
        configureRequest(params, request);
        return params.build();
    }

    /** Configures the selected provider's output schema, tools, and other server-specific settings. */
    protected abstract void configureRequest(ResponseCreateParams.Builder params, InferenceRequest request);

}
