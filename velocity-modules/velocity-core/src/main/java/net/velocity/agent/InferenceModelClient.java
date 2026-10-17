package net.velocity.agent;

import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

import net.velocity.inference.InferenceService;
import net.velocity.inference.TransientInferenceException;
import net.velocity.inference.objects.InferenceCapability;
import net.velocity.inference.objects.InferenceEvent;
import net.velocity.inference.objects.InferenceOptions.FunctionTool;
import net.velocity.inference.objects.InferenceRequest;
import net.velocity.inference.objects.InferenceResponse;
import net.velocity.inference.objects.InferenceResponseException;

public final class InferenceModelClient implements ModelClient {

    private final InferenceService inference;
    private final boolean streaming;

    public InferenceModelClient(InferenceService inference) {
        this(inference, false);
    }

    /** With {@code streaming}, turns that supply a text consumer use the provider's stream when it has one. */
    public InferenceModelClient(InferenceService inference, boolean streaming) {
        this.inference = inference;
        this.streaming = streaming;
    }

    @Override
    public boolean available() {
        return inference.available();
    }

    @Override
    public long outputTokenAllowance() {
        return inference.outputTokenAllowance();
    }

    @Override
    public OptionalLong countInputTokens(
            InferenceRequest request,
            List<ToolRegistry.Definition> tools,
            CancellationHandle cancellation) {
        cancellation.check();
        var count = inference.countInputTokens(withTools(request, tools), cancellation::check);
        cancellation.check();
        return count;
    }

    @Override
    public InferenceResponse infer(
            InferenceRequest request,
            List<ToolRegistry.Definition> tools,
            CancellationHandle cancellation) {
        cancellation.check();
        InferenceResponse response = inference.infer(withTools(request, tools), cancellation::check);
        // The runner records returned usage before checking cancellation again.
        return response;
    }

    @Override
    public InferenceResponse infer(
            InferenceRequest request,
            List<ToolRegistry.Definition> tools,
            CancellationHandle cancellation,
            Consumer<String> textDelta) {
        if (!streaming || textDelta == null
                || !inference.capabilities().contains(InferenceCapability.STREAMING)) {
            return infer(request, tools, cancellation);
        }
        cancellation.check();
        InferenceResponse complete = null;
        try (var stream = inference.stream(withTools(request, tools), cancellation::check)) {
            var events = stream.events().iterator();
            while (events.hasNext()) {
                InferenceEvent event = events.next();
                if (event.type() == InferenceEvent.Type.TEXT_DELTA && event.delta() != null) {
                    textDelta.accept(event.delta());
                } else if (event.type() == InferenceEvent.Type.COMPLETE) {
                    complete = event.response();
                }
                if (cancellation.cancelled()) {
                    break;
                }
            }
        } catch (InferenceResponseException | TransientInferenceException | CancellationException known) {
            throw known;
        } catch (RuntimeException failure) {
            // A stream can fail after the provider started generating, so its usage stays uncertain and the
            // runner retries with a new, separately accounted request identity.
            if (ProviderStreamFailure.cancelled(failure)) {
                throw failure;
            }
            throw new TransientInferenceException("provider_stream_failed", true, 0);
        }
        cancellation.check();
        if (complete == null) {
            throw new TransientInferenceException("provider_stream_incomplete", true, 0);
        }
        return complete;
    }

    private static InferenceRequest withTools(InferenceRequest request, List<ToolRegistry.Definition> tools) {
        var definitions = tools.stream().map(
                tool -> new FunctionTool(
                        tool.name(),
                        tool.description(),
                        tool.inputSchema()))
                .toList();
        return request.withOptions(request.options().withTools(definitions));
    }

    /** Interrupts and cancellations end the request on purpose; they are never retried as transient. */
    private static final class ProviderStreamFailure {

        private ProviderStreamFailure() {
        }

        static boolean cancelled(Throwable failure) {
            if (Thread.currentThread().isInterrupted()) {
                return true;
            }
            for (int depth = 0; failure != null && depth < 16; depth++, failure = failure.getCause()) {
                if (failure instanceof InterruptedException
                        || failure instanceof java.io.InterruptedIOException
                        || failure instanceof CancellationException) {
                    return true;
                }
            }
            return false;
        }
    }
}
