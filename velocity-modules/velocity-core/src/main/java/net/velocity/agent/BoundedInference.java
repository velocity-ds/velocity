package net.velocity.agent;

import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import net.velocity.inference.InferenceUsageScope;
import net.velocity.inference.TransientInferenceException;
import net.velocity.inference.objects.InferenceMessage;
import net.velocity.inference.objects.InferenceRequest;
import net.velocity.inference.objects.InferenceResponse;
import net.velocity.inference.objects.InferenceResponseException;
import net.velocity.usage.UsageLedger;

/** Shared request mechanics; callers retain admission, durable reservations and settlement policies. */
public final class BoundedInference {

    private BoundedInference() {
    }

    @FunctionalInterface
    public interface RemainingTime {

        long millis() throws Exception;
    }

    @FunctionalInterface
    public interface Checkpoint {

        void check() throws Exception;
    }

    public static ContextSizingPolicy.Estimate count(
            InferenceRequest request,
            List<InferenceMessage> diagnosticMessages,
            List<ToolRegistry.Definition> definitions,
            Callable<OptionalLong> counter,
            CancellationHandle cancellation,
            ExecutorService executor,
            RemainingTime remaining,
            Checkpoint checkpoint,
            long fallbackExtraBytes) throws Exception {
        var estimate = ContextSizingPolicy.estimate(diagnosticMessages, request.systemPrompt(), definitions);
        cancellation.check();
        checkpoint.check();
        var tokens = cancellation.await(executor, counter, remaining.millis());
        cancellation.check();
        checkpoint.check();
        remaining.millis();
        if (tokens.isPresent()) {
            return estimate.withProviderInputTokens(tokens);
        }
        return new ContextSizingPolicy.Estimate(
                estimate.messageBytes(),
                estimate.schemaBytes(),
                estimate.instructionBytes(),
                estimate.envelopeBytes(),
                Math.addExact(estimate.estimatedInputTokens(), fallbackExtraBytes),
                estimate.estimatorKind());
    }

    /** Account in the worker before cancellation can discard its Future result. */
    public static InferenceResponse infer(
            ModelClient model,
            InferenceRequest request,
            List<ToolRegistry.Definition> definitions,
            CancellationHandle cancellation,
            UsageLedger.Context context,
            BiConsumer<InferenceResponse.Usage, Boolean> observation) throws Exception {
        return infer(model, request, definitions, cancellation, context, observation, null);
    }

    /** The same request, relaying streamed assistant text to {@code textDelta} when the client streams. */
    public static InferenceResponse infer(
            ModelClient model,
            InferenceRequest request,
            List<ToolRegistry.Definition> definitions,
            CancellationHandle cancellation,
            UsageLedger.Context context,
            BiConsumer<InferenceResponse.Usage, Boolean> observation,
            Consumer<String> textDelta) throws Exception {
        try (var accounting = InferenceUsageScope.open(context)) {
            InferenceResponse response;
            try {
                response = textDelta == null
                        ? model.infer(request, definitions, cancellation)
                        : model.infer(request, definitions, cancellation, textDelta);
            } catch (InferenceResponseException rejected) {
                observation
                        .accept(rejected.usage(), rejected.usage() == null || rejected.usage().incomplete());
                throw rejected;
            } catch (TransientInferenceException failure) {
                // A refused request charged nothing; a lost connection keeps its reservation.
                observation.accept(
                        failure.possiblyBilled() ? null : new InferenceResponse.Usage(0, 0),
                        failure.possiblyBilled());
                throw failure;
            } catch (Exception failure) {
                observation.accept(null, true);
                throw failure;
            }
            var usage = response == null ? null : response.usage();
            observation.accept(usage, usage == null || usage.incomplete());
            return response;
        }
    }
}
