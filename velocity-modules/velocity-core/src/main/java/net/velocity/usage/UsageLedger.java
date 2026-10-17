package net.velocity.usage;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import net.velocity.inference.objects.InferenceResponse;
import net.velocity.inference.objects.InferenceResponse.Usage;

/** Begin is acknowledged before dispatch; observations replace one cumulative request, never append charges. */
public final class UsageLedger {

    private final UsageRepository repository;
    private final UsageProperties properties;
    private final Clock clock;
    private final UsageActivities activities;

    public UsageLedger(
            UsageRepository repository,
            UsageProperties properties,
            Clock clock,
            UsageActivities activities) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
        this.activities = Objects.requireNonNull(activities);
    }

    /** Only trusted adapters may construct this context; never bind it to a request DTO or model output. */
    public record Context(
            String ownerAccountId,
            String executionId,
            String rootExecutionId,
            String activity,
            String sourceTaskId,
            String sourceRequestKey) {

        public Context(
                String ownerAccountId,
                String executionId,
                String rootExecutionId,
                String activity,
                String sourceTaskId) {
            this(ownerAccountId, executionId, rootExecutionId, activity, sourceTaskId, null);
        }

        public Context {
            UsageActivities.requireWellFormed(activity);
            for (String value : new String[]{ownerAccountId, executionId, rootExecutionId, sourceTaskId,
                    sourceRequestKey}) {
                if (value != null && (value.isBlank() || value.length() > 1024)) {
                    throw new IllegalArgumentException("Invalid usage identity");
                }
            }
        }
    }

    public record Handle(String requestId) {}

    public Handle begin(Context context, String provider, String model) {
        return begin(new Handle(UUID.randomUUID().toString()), context, provider, model);
    }

    /** Reusing this handle only retries intent persistence; a new provider dispatch requires a fresh handle. */
    public Handle begin(Handle handle, Context context, String provider, String model) {
        Objects.requireNonNull(context);
        activities.require(context.activity());
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("Inference provider required");
        }
        if (model == null || model.isBlank()) {
            model = "unknown";
        }
        var record = new UsageRecord(
                handle.requestId(),
                context,
                clock.instant(),
                provider,
                model,
                model,
                false,
                properties.pricingVersion(),
                properties.prices(),
                null,
                false,
                1);
        repository.insert(record);
        return handle;
    }

    public void observe(Handle handle, InferenceResponse response, boolean complete) {
        update(
                handle,
                response == null ? null : response.usage(),
                response == null ? null : response.model(),
                complete);
    }

    private void update(Handle handle, Usage observed, String model, boolean complete) {
        for (int retry = 0; retry < 20; retry++) {
            var old = repository.find(handle.requestId())
                    .orElseThrow(() -> new IllegalStateException("Missing request intent"));
            // A late stale/failed observation cannot unsettle an already complete request.
            if (old.complete()) {
                return;
            }
            boolean nonRegressing = observed != null && (old.usage() == null
                    || observed.inputTokens() >= old.usage().inputTokens()
                            && observed.outputTokens() >= old.usage().outputTokens()
                            && observed.totalTokens() >= old.usage().totalTokens());
            boolean finalUsage = complete && nonRegressing && !observed.incomplete();
            // A complete response owns the category partition. Maxima across provisional
            // partitions can double-count categories or apply the wrong rates after a correction.
            var usage = finalUsage ? authoritative(old.usage(), observed) : cumulative(old.usage(), observed);
            boolean hasModel = model != null && !model.isBlank();
            String actual = hasModel ? model : old.model();
            // Requested-model fallback is not evidence of the actual model. The first
            // reported identity can arrive after usage; once reported it cannot change.
            if (old.responseModelObserved() && !old.model().equals(actual)) {
                usage = unsupported(usage, "response_model_changed");
                actual = old.model();
            }
            var updated = new UsageRecord(
                    old.requestId(),
                    old.context(),
                    old.startedAt(),
                    old.provider(),
                    old.requestedModel(),
                    actual,
                    old.responseModelObserved() || hasModel,
                    old.pricingVersion(),
                    old.priceCatalog(),
                    usage,
                    finalUsage,
                    old.revision() + 1);
            if (repository.replace(updated, old.revision())) {
                return;
            }
        }
        throw new IllegalStateException("Usage observation revision contention");
    }

    static Usage cumulative(Usage previous, Usage observed) {
        if (previous == null) {
            return observed;
        }
        if (observed == null) {
            return previous;
        }
        // Ignore stale cumulative observations, including their missing-usage flags.
        if (observed.inputTokens() < previous.inputTokens()
                || observed.outputTokens() < previous.outputTokens()
                || observed.totalTokens() < previous.totalTokens()) {
            return previous;
        }
        var unsupported = new LinkedHashSet<>(previous.unsupportedCategories());
        unsupported.addAll(observed.unsupportedCategories());
        return new Usage(
                observed.inputTokens(),
                observed.outputTokens(),
                observed.totalTokens(),
                Math.max(previous.cacheReadTokens(), observed.cacheReadTokens()),
                Math.max(previous.cacheCreationTokens(), observed.cacheCreationTokens()),
                Math.max(previous.cacheCreation5mTokens(), observed.cacheCreation5mTokens()),
                Math.max(previous.cacheCreation1hTokens(), observed.cacheCreation1hTokens()),
                Math.max(previous.reasoningTokens(), observed.reasoningTokens()),
                observed.incomplete(),
                java.util.List.copyOf(unsupported));
    }

    private static Usage authoritative(Usage previous, Usage observed) {
        if (previous == null) {
            return observed;
        }
        // An earlier unsupported billed category remains evidence even if the final event omits it.
        var categories = new LinkedHashSet<>(previous.unsupportedCategories());
        categories.addAll(observed.unsupportedCategories());
        return new Usage(
                observed.inputTokens(),
                observed.outputTokens(),
                observed.totalTokens(),
                observed.cacheReadTokens(),
                observed.cacheCreationTokens(),
                observed.cacheCreation5mTokens(),
                observed.cacheCreation1hTokens(),
                observed.reasoningTokens(),
                observed.incomplete(),
                java.util.List.copyOf(categories));
    }

    private static Usage unsupported(Usage usage, String category) {
        if (usage == null) {
            return new Usage(0, 0, 0, 0, 0, 0, 0, 0, true, java.util.List.of(category));
        }
        var categories = new LinkedHashSet<>(usage.unsupportedCategories());
        categories.add(category);
        return new Usage(
                usage.inputTokens(),
                usage.outputTokens(),
                usage.totalTokens(),
                usage.cacheReadTokens(),
                usage.cacheCreationTokens(),
                usage.cacheCreation5mTokens(),
                usage.cacheCreation1hTokens(),
                usage.reasoningTokens(),
                usage.incomplete(),
                java.util.List.copyOf(categories));
    }
}
