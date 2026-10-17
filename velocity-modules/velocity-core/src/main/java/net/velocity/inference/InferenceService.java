package net.velocity.inference;

import java.util.OptionalLong;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import net.velocity.inference.internal.AccountingInferenceStream;
import net.velocity.inference.objects.InferenceCapability;
import net.velocity.inference.objects.InferenceRequest;
import net.velocity.inference.objects.InferenceResponse;
import net.velocity.inference.objects.InferenceResponseException;
import net.velocity.usage.UsageLedger;

@Service
public class InferenceService {

    private final InferenceProvider inferenceProvider;
    private final UsageLedger usageLedger;
    private final InferenceRequestLimiter limiter;

    public InferenceService(InferenceProvider inferenceProvider) {
        this(inferenceProvider, null);
    }

    public InferenceService(InferenceProvider inferenceProvider, UsageLedger usageLedger) {
        this(
                inferenceProvider,
                usageLedger,
                new InferenceRequestLimiter(InferenceConcurrencyProperties.defaults()));
    }

    public InferenceService(
            InferenceProvider inferenceProvider,
            UsageLedger usageLedger,
            InferenceRequestLimiter limiter) {
        this.inferenceProvider = inferenceProvider;
        this.usageLedger = usageLedger;
        this.limiter = java.util.Objects.requireNonNull(limiter);
    }

    @Autowired
    public InferenceService(
            ObjectProvider<InferenceProvider> providers,
            ObjectProvider<UsageLedger> ledgers,
            InferenceRequestLimiter limiter) {
        this(providers.getIfAvailable(), ledgers.getIfAvailable(), limiter);
    }

    public boolean available() {
        return inferenceProvider != null;
    }

    /** Configured selected-provider output ceiling without construction, capability probes or remote I/O. */
    public long outputTokenAllowance() {
        return available() ? inferenceProvider.outputTokenAllowance() : 2048;
    }

    private InferenceProvider requiredProvider() {
        if (!available()) {
            throw new IllegalStateException("Inference is not configured");
        }
        return inferenceProvider;
    }

    public String infer(String input) {
        return infer(InferenceRequest.text(input)).text();
    }

    /** Counting has no generation or usage-ledger dispatch; only the selected provider sees the request. */
    public OptionalLong countInputTokens(InferenceRequest request) {
        return countInputTokens(request, () -> {});
    }

    public OptionalLong countInputTokens(InferenceRequest request, Runnable cancellationCheck) {
        requiredProvider().requireCapabilities(request, false);
        try (var permit = limiter.acquire(cancellationCheck)) {
            cancellationCheck.run();
            var count = inferenceProvider.countInputTokens(request);
            if (count == null || count.isPresent() && count.getAsLong() < 0) {
                throw new InputTokenCountException();
            }
            return count;
        }
    }

    public InferenceResponse infer(InferenceRequest request) {
        return infer(request, () -> {});
    }

    public InferenceResponse infer(InferenceRequest request, Runnable cancellationCheck) {
        requiredProvider().requireCapabilities(request, false);
        try (var permit = limiter.acquire(cancellationCheck)) {
            cancellationCheck.run();
            var handle = begin();
            long started = System.nanoTime();
            try {
                var response = inferenceProvider.infer(request);
                if (handle != null) {
                    usageLedger.observe(handle, response, true);
                }
                metrics("completed", started, response);
                return response;
            } catch (InferenceResponseException failure) {
                observeFailure(handle, failure, true);
                metrics("rejected", started, null);
                throw failure;
            } catch (TransientInferenceException failure) {
                // An error status is a refused request with no charge. A lost connection keeps its
                // intent uncertain, because the provider may have generated before the drop.
                if (!failure.possiblyBilled()) {
                    observeRefusal(handle, failure);
                }
                metrics(failure.code(), started, null);
                throw failure;
            } catch (RuntimeException failure) {
                metrics("failed", started, null);
                throw failure;
            }
        }
    }

    /** Request timer and token counters per provider and model; usage is counted once, when known. */
    private void metrics(String outcome, long startedNanos, InferenceResponse response) {
        String model = response != null && response.model() != null
                ? response.model()
                : inferenceProvider.configuredModel();
        net.velocity.agent.AgentMetrics.inferenceRequest(
                inferenceProvider.providerId(),
                model,
                outcome,
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
        if (response != null) {
            net.velocity.agent.AgentMetrics
                    .inferenceTokens(inferenceProvider.providerId(), model, response.usage());
        }
    }

    private void observeRefusal(UsageLedger.Handle handle, TransientInferenceException failure) {
        if (handle == null) {
            return;
        }
        try {
            usageLedger.observe(
                    handle,
                    new InferenceResponse(
                            null,
                            null,
                            java.util.List.of(),
                            null,
                            new InferenceResponse.Usage(0, 0),
                            ""),
                    true);
        } catch (RuntimeException accountingFailure) {
            failure.addSuppressed(accountingFailure);
        }
    }

    public InferenceStream stream(String input) {
        return stream(InferenceRequest.text(input));
    }

    /** The caller must close the returned stream with try-with-resources. */
    public InferenceStream stream(InferenceRequest request) {
        return stream(request, () -> {});
    }

    public InferenceStream stream(InferenceRequest request, Runnable cancellationCheck) {
        requiredProvider().requireCapabilities(request, true);
        var permit = limiter.acquire(cancellationCheck);
        try {
            cancellationCheck.run();
            var handle = begin();
            try {
                var stream = inferenceProvider.stream(request);
                var accounted = handle == null
                        ? stream
                        : new AccountingInferenceStream(stream, usageLedger, handle);
                long started = System.nanoTime();
                return new InferenceStream() {

                    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
                    private final java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean();

                    @Override
                    public java.util.stream.Stream<net.velocity.inference.objects.InferenceEvent> events() {
                        // The completion event carries the assembled usage; a stream closed early counts as such.
                        return accounted.events().peek(event -> {
                            if (event
                                    .type() == net.velocity.inference.objects.InferenceEvent.Type.COMPLETE
                                    && completed.compareAndSet(false, true)) {
                                metrics("streamed", started, event.response());
                            }
                        });
                    }

                    @Override
                    public void close() {
                        if (closed.compareAndSet(false, true)) {
                            try {
                                accounted.close();
                            } finally {
                                permit.close();
                                if (!completed.get()) {
                                    metrics("stream_closed", started, null);
                                }
                            }
                        }
                    }
                };
            } catch (InferenceResponseException failure) {
                observeFailure(handle, failure, false);
                throw failure;
            }
        } catch (RuntimeException | Error failure) {
            permit.close();
            throw failure;
        }
    }

    private UsageLedger.Handle begin() {
        // Persist uncertainty first. A failed or interrupted provider request remains visible even
        // when no final usage arrives; retries at the caller are new billable request identities.
        return usageLedger == null
                ? null
                : usageLedger.begin(
                        InferenceUsageScope.current(),
                        inferenceProvider.providerId(),
                        inferenceProvider.configuredModel());
    }

    private void observeFailure(
            UsageLedger.Handle handle,
            InferenceResponseException failure,
            boolean complete) {
        if (handle == null) {
            return;
        }
        try {
            usageLedger.observe(
                    handle,
                    new InferenceResponse(
                            null,
                            failure.model(),
                            java.util.List.of(),
                            null,
                            failure.usage(),
                            ""),
                    complete);
        } catch (RuntimeException accountingFailure) {
            failure.addSuppressed(accountingFailure);
        }
    }

    public String providerId() {
        return requiredProvider().providerId();
    }

    public Set<InferenceCapability> capabilities() {
        return available() ? Set.copyOf(inferenceProvider.capabilities()) : Set.of();
    }
}
