package net.velocity.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.velocity.agent.persistence.ConflictException;
import net.velocity.agent.persistence.ExecutionRepository;
import net.velocity.agent.state.*;
import net.velocity.inference.InferenceProfileRegistry;
import net.velocity.inference.SystemPrompts;
import net.velocity.inference.objects.InferenceMessage;
import net.velocity.inference.objects.InferenceOptions;
import net.velocity.inference.objects.InferenceRequest;

/**
 * Prepares the context of one model turn at a settled request boundary: counts the complete prepared request
 * against the pinned profile and the task budget, commits a compact view of a long conversation when the
 * original no longer fits, and hands an over-long llama.cpp conversation to the configured cloud profile. It
 * never truncates saved messages or splits native call/result pairs. {@link ConversationRunner} owns the
 * loop and decides what to do with each {@link ContextDecision}.
 */
final class ContextPreparer {

    private static final Logger log = LoggerFactory.getLogger(ContextPreparer.class);
    private final ExecutionRepository repository;
    private final ChildRunRepository childRepository;
    private final InferenceProfileRegistry profiles;
    private final ConversationContext conversation;
    private final ExecutionProgress progress;
    private final Function<ExecutionSnapshot, ModelClient> models;
    private final ExecutorService operations;

    /**
     * @param models resolves the pinned model client for a snapshot; the runner owns streaming and profile
     *        resolution
     * @param operations the runner's executor for bounded token counting
     */
    ContextPreparer(
            ExecutionRepository repository,
            InferenceProfileRegistry profiles,
            ConversationContext conversation,
            ExecutionProgress progress,
            Function<ExecutionSnapshot, ModelClient> models,
            ExecutorService operations) {
        this.repository = repository;
        this.childRepository = repository instanceof ChildRunRepository child ? child : null;
        this.profiles = profiles;
        this.conversation = conversation;
        this.progress = progress;
        this.models = models;
        this.operations = operations;
    }

    /**
     * Sizes the prepared request and, when a root conversation has grown past 80% of its effective allowance,
     * tries a compact view backed by an evidence artifact. A compact view is committed to the snapshot at the
     * same settled boundary before it is used.
     */
    ContextDecision boundedContext(
            ExecutionSnapshot snapshot,
            String instructions,
            List<ToolRegistry.Definition> definitions,
            CancellationHandle cancellation,
            long sizingBegan) throws Exception {
        List<InferenceMessage> context;
        try {
            context = conversation.preparedMessages(snapshot);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            context = List.of();
        }
        var original = sizeContext(
                snapshot,
                context,
                instructions,
                definitions,
                snapshot.activeInferenceBinding(),
                models.apply(snapshot),
                cancellation,
                sizingBegan);
        long allowance = snapshot.activeInferenceBinding().inputAllowance();
        long remaining = progress.remainingTokens(snapshot);
        long effective = ContextSizingPolicy
                .allowance(remaining, allowance, progress.outputAllowance(snapshot));
        // Start at 80% to retain growth and final-reporting room. Never spend another model request on summaries.
        if (childRepository != null || snapshot.partialReporting != null || effective == 0
                || definitions.stream().noneMatch(tool -> "task_evidence_read".equals(tool.name()))
                || snapshot.inference.compact() != null
                        && snapshot.inference.compact().sourceMessageCount() == snapshot.messages.size()
                || original.estimate().estimatedInputTokens() < effective - effective / 5) {
            return original;
        }
        var unabridged = conversation.uncompactedMessages(snapshot);
        var compact = ConversationCompactor.prepare(snapshot, unabridged, "pending-evidence-index");
        if (!compact.supported()) {
            repository.diagnostic(snapshot.executionId, "context_compaction_unavailable", compact.failure());
            return original;
        }
        String artifact = repository.artifact(snapshot.executionId, compact.evidence());
        compact = ConversationCompactor.prepare(snapshot, unabridged, artifact);
        if (!compact.supported()) {
            return original;
        }
        var candidate = sizeContext(
                snapshot,
                compact.messages(),
                instructions,
                definitions,
                snapshot.activeInferenceBinding(),
                models.apply(snapshot),
                cancellation,
                sizingBegan);
        if (!candidate.accepted() || candidate.estimate().estimatedInputTokens() >= original.estimate()
                .estimatedInputTokens()) {
            repository.diagnostic(
                    snapshot.executionId,
                    "context_compaction_unavailable",
                    "compact_view_does_not_fit_or_reduce_input");
            return original;
        }
        var view = new CompactRequestView(
                snapshot.revision,
                snapshot.messages.size(),
                snapshot.historyGeneration,
                snapshot.goalGeneration,
                snapshot.activeInferenceBinding(),
                compact.messages(),
                artifact);
        var committed = repository.update(snapshot.executionId, snapshot.revision, state -> {
            requireCurrentBoundary(state, cancellation);
            state.inference = state.inference.withCompact(view);
        });
        return new ContextDecision(
                candidate.messages(),
                candidate.estimate(),
                candidate.outcome(),
                candidate.diagnostics(),
                committed.revision);
    }

    private ContextDecision sizeContext(
            ExecutionSnapshot snapshot,
            List<InferenceMessage> context,
            String instructions,
            List<ToolRegistry.Definition> definitions,
            InferenceBinding binding,
            ModelClient selectedModel,
            CancellationHandle cancellation,
            long sizingBegan) throws Exception {
        // Count the complete prepared request, including trusted instructions and tool
        // schemas. Preserve all saved messages; never silently truncate a thread or
        // split native call/result pairs when the conservative estimate cannot fit.
        var estimate = countedEstimate(
                snapshot,
                context,
                instructions,
                definitions,
                selectedModel,
                cancellation,
                sizingBegan,
                false);
        long remaining = progress.remainingTokens(snapshot);
        long inputAllowance = binding.inputAllowance();
        long outputReserve = progress.budget(snapshot).outputAllowance(snapshot, binding.maxOutputTokens());
        var outcome = ContextSizingPolicy
                .decide(estimate, !context.isEmpty(), remaining, inputAllowance, outputReserve);
        Map<String, Object> diagnostics = new LinkedHashMap<>(
                estimate.diagnostics(
                        remaining,
                        inputAllowance,
                        outputReserve,
                        binding.profileId(),
                        binding.providerId(),
                        binding.model()));
        diagnostics.put("decision", outcome.name());
        diagnostics.put("inference_stage", snapshot.inference.transition() == null ? "original" : "cloud");
        diagnostics.put("reporting_mode", snapshot.partialReporting == null ? "normal" : "partial_answer");
        for (String key : List.of(
                "continuation_outcome",
                "source_estimated_input_tokens",
                "cloud_estimated_input_tokens")) {
            if (snapshot.contextDiagnostics.containsKey(key)) {
                diagnostics.put(key, snapshot.contextDiagnostics.get(key));
            }
        }
        log.info(
                "executionId={} run={} stage=context sizes={}",
                snapshot.executionId,
                childRepository == null ? "root" : childRepository.childId,
                diagnostics);
        return new ContextDecision(context, estimate, outcome, diagnostics, snapshot.revision);
    }

    private ContextSizingPolicy.Estimate countedEstimate(
            ExecutionSnapshot snapshot,
            List<InferenceMessage> messages,
            String instructions,
            List<ToolRegistry.Definition> definitions,
            ModelClient selectedModel,
            CancellationHandle cancellation,
            long sizingBegan,
            boolean completion) throws Exception {
        if (messages.isEmpty()) {
            return ContextSizingPolicy.estimate(messages, instructions, definitions);
        }
        var request = new InferenceRequest(
                completion ? ContextSizingPolicy.completionMessages(snapshot, messages) : messages,
                instructions,
                InferenceOptions.defaults());
        return BoundedInference.count(
                request,
                messages,
                definitions,
                () -> selectedModel.countInputTokens(request, definitions, cancellation),
                cancellation,
                operations,
                () -> requireRemainingTime(snapshot, sizingBegan),
                cancellation::check,
                0);
    }

    /** The active time left for this turn, net of the sizing already spent when the budget deducts it. */
    long requireRemainingTime(ExecutionSnapshot snapshot, long sizingBegan) throws TimeoutException {
        long remaining = progress.remaining(snapshot);
        if (progress.budget(snapshot).deductsElapsedTime(childRepository == null)) {
            remaining -= TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sizingBegan);
        }
        if (remaining <= 0) {
            throw new TimeoutException("Active time limit reached");
        }
        return remaining;
    }

    /** The stop reason recorded when a sized context was not accepted. */
    static String limitReason(ContextDecision decision) {
        return switch (decision.outcome()) {
            case input_allowance -> "context_limit";
            case remaining_budget -> "context_budget_limit";
            case invalid_context -> "invalid_context";
            case accepted -> throw new IllegalArgumentException("Accepted context has no limit reason");
        };
    }

    /**
     * A prepared request may only be committed at a settled boundary: running, not cancelled, no unsettled
     * call or child, and no pending source message or correction. Throws {@link ConflictException} otherwise.
     */
    void requireCurrentBoundary(ExecutionSnapshot snapshot, CancellationHandle cancellation) {
        if (snapshot.status != ExecutionStatus.running || snapshot.cancellationRequested
                || cancellation.cancelled()
                || !cancellation.settledForCaller()
                || snapshot.calls.stream().anyMatch(call -> call.state != CallState.settled)
                || snapshot.childRuns.values().stream().anyMatch(child -> !child.status.terminal())
                || !snapshot.pendingSourceMessages.isEmpty()
                || repository.inbox(snapshot.executionId).stream()
                        .anyMatch(c -> !snapshot.consumedInboxIds.contains(c.id()))) {
            throw new ConflictException("Request boundary changed");
        }
    }

    /**
     * Moves a root llama.cpp conversation that exceeded its input allowance to the configured cloud profile.
     * Returns the continuation outcome recorded in the diagnostics: {@code transitioned}, {@code stale}, or
     * the reason the handoff was not possible.
     */
    String continueInCloud(
            ExecutionSnapshot snapshot,
            ContextDecision source,
            String sourceInstructions,
            List<ToolRegistry.Definition> available,
            CancellationHandle cancellation,
            long sizingBegan) throws Exception {
        if (profiles == null) {
            return "profile_unavailable";
        }
        var configured = profiles.find(snapshot.inference.fallback().profileId());
        if (configured.isEmpty()) {
            return "profile_unavailable";
        }
        if (!snapshot.inference.fallback().matches(configured.get())) {
            return "profile_incompatible";
        }
        // A tokenizer call can outlast a correction. Recheck before sending any context to cloud.
        var current = repository.get(snapshot.executionId);
        if (current.revision != snapshot.revision) {
            return "stale";
        }
        try {
            requireCurrentBoundary(current, cancellation);
        } catch (ConflictException stale) {
            return "stale";
        }
        var handoff = CloudHandoff.prepare(snapshot, source.messages());
        if (!handoff.supported()) {
            return handoff.outcome();
        }
        var cloud = sizeContext(
                snapshot,
                handoff.messages(),
                sourceInstructions
                        + "\n" + SystemPrompts.CLOUD_CONTINUATION_INSTRUCTIONS,
                available,
                snapshot.inference.fallback(),
                new InferenceModelClient(configured.get().service()),
                cancellation,
                sizingBegan);
        if (!cloud.accepted()) {
            return "cloud_" + cloud.outcome().name();
        }
        try {
            repository.update(snapshot.executionId, snapshot.revision, s -> {
                requireCurrentBoundary(s, cancellation);
                s.inference = s.inference.withTransition(
                        new InferenceTransition(
                                "input_allowance_exceeded",
                                snapshot.revision,
                                snapshot.messages.size(),
                                snapshot.historyGeneration,
                                snapshot.modelTurns,
                                snapshot.goalGeneration,
                                handoff.messages()));
                var diagnostics = new LinkedHashMap<>(cloud.diagnostics());
                diagnostics.put("inference_stage", "cloud");
                diagnostics.put("continuation_outcome", "transitioned");
                diagnostics.put("source_estimated_input_tokens", source.estimate().estimatedInputTokens());
                diagnostics.put("cloud_estimated_input_tokens", cloud.estimate().estimatedInputTokens());
                s.contextDiagnostics = diagnostics;
            });
            return "transitioned";
        } catch (ConflictException stale) {
            return "stale";
        }
    }
}
