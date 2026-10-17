package net.velocity.agent;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.velocity.agent.persistence.ConflictException;
import net.velocity.agent.persistence.ExecutionRepository;
import net.velocity.agent.state.*;
import net.velocity.inference.InferenceProfileRegistry;
import net.velocity.inference.InferenceUsageScope;
import net.velocity.inference.InputTokenCountException;
import net.velocity.inference.SystemPrompts;
import net.velocity.inference.TransientInferenceException;
import net.velocity.inference.objects.*;
import net.velocity.usage.UsageLedger;

/** Native model turns, response acceptance and conversation progression. */
public final class ConversationRunner implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ConversationRunner.class);
    /** Retries after the first transient provider failure; the delays double from the base. */
    private static final int MAX_TRANSIENT_RETRIES = 4;
    private static final long TRANSIENT_BASE_DELAY_MILLIS = 2_000;
    private static final long TRANSIENT_MAX_DELAY_MILLIS = 60_000;
    private final ExecutionRepository repository;
    private final ModelClient model;
    private final InferenceProfileRegistry profiles;
    private final ContextHooks hooks;
    private final ChildRunRepository childRepository;
    private final ConversationContext context;
    private final ExecutionProgress progress;
    private final ToolCallExecutor toolCalls;
    private final ExecutorService operations = Executors.newVirtualThreadPerTaskExecutor();
    private final ContextPreparer preparer;
    /** Root runs only; a child never interposes the end-of-task skill check. */
    private volatile boolean skillSelfCheck;
    /** Live console output; null when nothing subscribes. Root runs only. */
    private volatile ExecutionEvents events;
    private volatile boolean modelStreaming;

    ConversationRunner(
            ExecutionRepository repository,
            ModelClient model,
            ContextHooks hooks,
            InferenceProfileRegistry profiles,
            ConversationContext context,
            ExecutionProgress progress,
            ToolCallExecutor toolCalls) {
        this.repository = repository;
        this.model = model;
        this.hooks = hooks;
        this.profiles = profiles;
        this.childRepository = repository instanceof ChildRunRepository child ? child : null;
        this.context = context;
        this.progress = progress;
        this.toolCalls = toolCalls;
        this.preparer = new ContextPreparer(
                repository,
                profiles,
                context,
                progress,
                this::modelFor,
                operations);
    }

    /** Enables the Hermes-style skill self-check before a root task's final answer is accepted. */
    void setSkillSelfCheck(boolean enabled) {
        this.skillSelfCheck = enabled;
    }

    /** Publishes streamed assistant text of root model turns to live console subscribers. */
    void setLiveOutput(ExecutionEvents events, boolean modelStreaming) {
        this.events = events;
        this.modelStreaming = modelStreaming;
    }

    private void clearLiveText(String id) {
        var live = events;
        if (live != null && childRepository == null) {
            live.clear(id);
        }
    }

    public void run(String id, CancellationHandle cancellation) {
        long began = System.nanoTime();
        long previous = repository.get(id).activeMillis;
        var run = new Run(id, cancellation, previous, began);
        // Every log line of this worker carries the execution it serves.
        org.slf4j.MDC.put("executionId", id);
        try {
            hooks.started(repository.get(id));
            while (iterate(run)) {
                // Every iteration ends by restarting (true) or by leaving the run (false).
            }
        } catch (ProfileUnavailableException e) {
            toolCalls.settleUndispatched(id, e.getMessage());
            progress.stop(id, ExecutionStatus.blocked, e.getMessage());
        } catch (net.velocity.inference.InferenceCapacityException e) {
            if (run.providerIntent != null) {
                progress.recordUsage(id, run.providerIntent, new InferenceResponse.Usage(0, 0), false);
            }
            toolCalls.settleUndispatched(id, e.code());
            progress.stop(id, ExecutionStatus.partial, e.code());
        } catch (InputTokenCountException e) {
            toolCalls.settleDispatched(id, "uncertain");
            toolCalls.settleUndispatched(id, "input_token_count_failed");
            progress.stop(id, ExecutionStatus.failed, "input_token_count_failed");
        } catch (AdmissionContext.IncompatibleToolException e) {
            toolCalls.settleUndispatched(id, "admitted_tool_incompatible");
            progress.stop(id, ExecutionStatus.blocked, e.getMessage());
        } catch (ChildRunRepository.CapacityException e) {
            toolCalls.settleUndispatched(id, e.getMessage());
            progress.stop(id, ExecutionStatus.partial, e.getMessage());
        } catch (CancellationException | InterruptedException e) {
            Thread.interrupted();
            toolCalls.settleUndispatched(id, "cancelled");
            toolCalls.settleDispatched(id, "uncertain");
            progress.stop(id, ExecutionStatus.cancelled, "cancelled");
        } catch (TimeoutException e) {
            progress.checkpoint(id, previous, began);
            if (repository.get(id).status == ExecutionStatus.running) {
                toolCalls.settleDispatched(id, "uncertain");
                toolCalls.settleUndispatched(id, "active_time_limit");
                var stopped = repository.get(id);
                progress.stop(
                        id,
                        ExecutionStatus.partial,
                        stopped.partialReporting == null
                                ? "active_time_limit"
                                : stopped.partialReporting.reason());
            }
        } catch (Exception e) {
            log.warn(
                    "executionId={} stage=runner outcome=failed exception={}",
                    id,
                    e.getClass().getSimpleName());
            toolCalls.settleDispatched(id, "uncertain");
            toolCalls.settleUndispatched(id, "execution_failed");
            var failed = repository.get(id);
            progress.stop(
                    id,
                    failed.partialReporting != null && failed.partialReporting.attempted()
                            ? ExecutionStatus.partial
                            : ExecutionStatus.failed,
                    failed.partialReporting != null && failed.partialReporting.attempted()
                            ? failed.partialReporting.reason()
                            : "execution_failed:" + e.getClass().getSimpleName());
        } finally {
            progress.checkpoint(id, previous, began);
            clearLiveText(id);
            try {
                hooks.completed(repository.get(id));
            } catch (RuntimeException e) {
                log.warn("executionId={} stage=hook outcome=failed", id);
            }
            org.slf4j.MDC.remove("executionId");
        }
    }

    /** How a phase hands control back to the loop. */
    private enum Step {
        /** Start the next loop iteration (the original {@code continue}). */
        RESTART,
        /** Leave the run (the original {@code return}). */
        FINISHED,
        /** Fall through to the next phase decision within this iteration. */
        ADVANCE
    }

    /** Mutable state shared by the phases of one run. */
    private static final class Run {

        final String id;
        final CancellationHandle cancellation;
        final long previous;
        final long began;
        ExecutionSnapshot snapshot;
        long sizingBegan;
        String providerIntent;
        /** Consecutive transient provider failures; reset by the next received response. */
        int transientFailures;

        Run(String id, CancellationHandle cancellation, long previous, long began) {
            this.id = id;
            this.cancellation = cancellation;
            this.previous = previous;
            this.began = began;
        }
    }

    /** One loop iteration; returns whether the loop should run again. */
    private boolean iterate(Run run) throws Exception {
        String id = run.id;
        // A capacity rejection can release only this iteration's undispatched request.
        run.providerIntent = null;
        progress.checkpoint(id, run.previous, run.began);
        if (consume(id, run.cancellation)) {
            return false;
        }
        run.snapshot = repository.get(id);
        run.sizingBegan = System.nanoTime();
        var phase = RunPhase.next(run.snapshot, facts(run.snapshot), RunPhase.STOP_NOT_RUNNING);
        while (true) {
            Step step = execute(phase, run);
            // A switch expression keeps this exhaustive: a new Step value fails to compile.
            boolean leave = switch (step) {
                case RESTART, FINISHED -> true;
                case ADVANCE -> false;
            };
            if (leave) {
                return step == Step.RESTART;
            }
            phase = RunPhase.next(run.snapshot, facts(run.snapshot), phase.following());
        }
    }

    private RunFacts facts(ExecutionSnapshot snapshot) {
        return new RunFacts(
                progress.budget(snapshot),
                () -> progress.remaining(snapshot),
                () -> progress.modelRequests(snapshot),
                () -> progress.charged(snapshot),
                () -> progress.taskCharged(snapshot));
    }

    private Step execute(RunPhase phase, Run run) throws Exception {
        return switch (phase) {
            case STOP_NOT_RUNNING -> Step.FINISHED;
            case STOP_AT_TIME_LIMIT -> stopAtTimeLimit(run);
            case STOP_AT_TOKEN_CEILING -> stopAtTokenCeiling(run);
            case WAIT_FOR_QUESTION -> waitForQuestion(run);
            case STOP_REPORTING_ATTEMPTED -> stopReportingAttempted(run);
            case SETTLE_REPORTING_CALLS -> settleReportingCalls(run);
            case PROCESS_ACCEPTED_CALLS -> processAcceptedCalls(run);
            case ADMIT_SOURCE_MESSAGES -> admitSourceMessages(run);
            case AWAIT_REPORTING_SETTLEMENT -> awaitReportingSettlement(run);
            case STOP_MODEL_BUDGET -> stopModelBudget(run);
            case MODEL_TURN -> modelTurn(run);
        };
    }

    private Step stopAtTimeLimit(Run run) {
        String id = run.id;
        toolCalls.settleAtTimeLimit(id);
        if (repository.get(id).status == ExecutionStatus.running) {
            progress.stop(id, ExecutionStatus.partial, "active_time_limit");
        }
        return Step.FINISHED;
    }

    /** Same boundary as the time limit: settle saved work without a further model request. */
    private Step stopAtTokenCeiling(Run run) {
        String id = run.id;
        toolCalls.settleAtTokenCeiling(id);
        if (repository.get(id).status == ExecutionStatus.running) {
            progress.stop(id, ExecutionStatus.partial, "task_token_ceiling");
        }
        return Step.FINISHED;
    }

    private Step waitForQuestion(Run run) {
        waiting(run.id);
        return Step.FINISHED;
    }

    private Step stopReportingAttempted(Run run) {
        // A crash can leave the reporting response's calls accepted but not yet rejected.
        // They must never reach the normal dispatch/recovery path.
        toolCalls.settleUndispatched(run.id, "reporting_tools_disabled");
        progress.stop(run.id, ExecutionStatus.partial, run.snapshot.partialReporting.reason());
        return Step.FINISHED;
    }

    private Step settleReportingCalls(Run run) {
        toolCalls.settleUndispatched(run.id, "reporting_tools_disabled");
        run.snapshot = repository.get(run.id);
        return Step.ADVANCE;
    }

    private Step processAcceptedCalls(Run run) throws Exception {
        String id = run.id;
        toolCalls.processAcceptedCalls(id, run.cancellation, run.previous, run.began);
        var processed = repository.get(id);
        // Preserve the question or approval boundary before the next active-time checkpoint.
        if (processed.status == ExecutionStatus.running
                && (!processed.pendingQuestions.isEmpty() || ToolCallExecutor.awaitingApproval(processed))) {
            waiting(id);
        }
        if (repository.get(id).status != ExecutionStatus.running) {
            return Step.FINISHED;
        }
        return Step.RESTART;
    }

    private Step admitSourceMessages(Run run) {
        run.snapshot = update(run.id, s -> {
            SourceContext.append(s, s.pendingSourceMessages);
            s.pendingSourceMessages.clear();
        });
        return Step.ADVANCE;
    }

    private Step awaitReportingSettlement(Run run) throws InterruptedException {
        String id = run.id;
        if (!progress.awaitSettlement(id, run.cancellation, run.previous, run.began)) {
            progress.stop(id, ExecutionStatus.partial, run.snapshot.partialReporting.reason());
            return Step.FINISHED;
        }
        progress.checkpoint(id, run.previous, run.began);
        run.snapshot = repository.get(id);
        run.sizingBegan = System.nanoTime();
        return Step.ADVANCE;
    }

    private Step stopModelBudget(Run run) {
        progress.stop(run.id, ExecutionStatus.partial, "model_budget_limit");
        return Step.FINISHED;
    }

    /**
     * Waits, then restarts the loop so the next model turn is a new, separately accounted request. A model
     * request or token count has no external effect, so repeating it is safe. Stops the task as failed with
     * the failure code once retries run out or the wait would outlast the remaining active time.
     */
    private Step retryTransientFailure(Run run, String code, long retryAfterMillis)
            throws InterruptedException {
        String id = run.id;
        run.transientFailures++;
        long backoff = Math.min(
                TRANSIENT_MAX_DELAY_MILLIS,
                TRANSIENT_BASE_DELAY_MILLIS << Math.min(16, run.transientFailures - 1));
        long delay = Math
                .max(retryAfterMillis, backoff + ThreadLocalRandom.current().nextLong(backoff / 4 + 1));
        progress.checkpoint(id, run.previous, run.began);
        long remaining = progress.remaining(repository.get(id));
        repository.diagnostic(id, code, "transient provider failure, attempt " + run.transientFailures);
        if (run.transientFailures > MAX_TRANSIENT_RETRIES || delay >= remaining) {
            log.warn(
                    "executionId={} stage=model outcome=transient_failure_exhausted code={} attempts={}",
                    id,
                    code,
                    run.transientFailures);
            progress.stop(id, ExecutionStatus.failed, code);
            return Step.FINISHED;
        }
        log.warn(
                "executionId={} stage=model outcome=transient_failure code={} attempt={} retryInMs={}",
                id,
                code,
                run.transientFailures,
                delay);
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delay);
        while (true) {
            run.cancellation.check();
            long left = TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime());
            if (left <= 0) {
                return Step.RESTART;
            }
            Thread.sleep(Math.min(250, left));
        }
    }

    /** Values prepared by one model-turn stage and consumed by the next. */
    private static final class Turn {

        boolean reporting;
        String instructions;
        List<ToolRegistry.Definition> available;
        ContextDecision decision;
        InferenceRequest request;
        ModelClient selectedModel;
        long start;
        String usageSource;
        int modelTurn;
        UsageLedger.Context usageContext;
        InferenceResponse response;
        List<AcceptedCall> accepted;
    }

    private Step modelTurn(Run run) throws Exception {
        var turn = new Turn();
        Step step;
        try {
            step = prepareContext(run, turn);
        } catch (InputTokenCountException countFailure) {
            // Counting is read-only and runs at a settled boundary, so a transient failure can wait and recount.
            if (!countFailure.transientFailure() || childRepository != null) {
                throw countFailure;
            }
            return retryTransientFailure(run, "input_token_count_failed", 0);
        }
        if (step == Step.ADVANCE) {
            step = startModelRequest(run, turn);
        }
        if (step == Step.ADVANCE) {
            step = awaitModelResponse(run, turn);
        }
        if (step == Step.ADVANCE) {
            step = acceptResponse(run, turn);
        }
        if (step == Step.ADVANCE) {
            step = settleAcceptedResponse(run, turn);
        }
        return step;
    }

    /** Sizes the request; may hand the run to the reporting reserve, cloud, or a stop instead. */
    private Step prepareContext(Run run, Turn turn) throws Exception {
        String id = run.id;
        CancellationHandle cancellation = run.cancellation;
        ExecutionSnapshot snapshot = run.snapshot;
        boolean repairing = childRepository != null && childRepository.child().repairAttempts > 0;
        turn.reporting = snapshot.partialReporting != null || repairing
                || progress.budget(snapshot).finalReportRequired(snapshot, progress);
        turn.instructions = this.context.instructions(snapshot, turn.reporting);
        turn.available = turn.reporting ? List.of() : this.context.availableTools(snapshot);
        try {
            turn.decision = preparer.boundedContext(
                    snapshot,
                    turn.instructions,
                    turn.available,
                    cancellation,
                    run.sizingBegan);
        } catch (ConflictException stale) {
            return Step.RESTART;
        }
        // Compaction commits its exact view at the same settled request boundary.
        snapshot = repository.get(id);
        run.snapshot = snapshot;
        if (childRepository == null && snapshot.revision != turn.decision.expectedRevision()) {
            return Step.RESTART;
        }
        if (reserveFinalReport(run, turn)) {
            return Step.RESTART;
        }
        if (!turn.decision.accepted()) {
            return stopOrContinueRejectedContext(run, turn);
        }
        return Step.ADVANCE;
    }

    /** Returns true when the loop must restart after switching the run to its final reporting turn. */
    private boolean reserveFinalReport(Run run, Turn turn) {
        String id = run.id;
        CancellationHandle cancellation = run.cancellation;
        ExecutionSnapshot snapshot = run.snapshot;
        var decision = turn.decision;
        if (childRepository == null && snapshot.modelTurns > 0 && !turn.reporting && decision.accepted()
                && progress.budget(snapshot).finalReportReserveRequired(
                        snapshot,
                        progress,
                        decision.estimate().estimatedInputTokens())) {
            try {
                repository.update(id, snapshot.revision, state -> {
                    preparer.requireCurrentBoundary(state, cancellation);
                    state.partialReporting = PartialReporting.pending(
                            "final_reporting_reserve",
                            state.modelTurns + 1);
                    state.unusableResponses = 0;
                });
            } catch (ConflictException stale) {
                return true;
            }
            return true;
        }
        return false;
    }

    /** The context was not accepted: stop, continue in the cloud, or enter partial reporting. */
    private Step stopOrContinueRejectedContext(Run run, Turn turn) throws Exception {
        String id = run.id;
        CancellationHandle cancellation = run.cancellation;
        ExecutionSnapshot snapshot = run.snapshot;
        var decision = turn.decision;
        if (childRepository != null) {
            progress.stop(id, ExecutionStatus.partial, ContextPreparer.limitReason(decision));
            return Step.FINISHED;
        }
        if (snapshot.partialReporting == null) {
            String continuation = "ineligible";
            if (decision.outcome() == ContextSizingPolicy.Decision.input_allowance
                    && snapshot.inference.transition() == null && snapshot.inference.fallback() != null
                    && "llama-cpp".equals(snapshot.inference.binding().providerId())) {
                continuation = preparer.continueInCloud(
                        snapshot,
                        decision,
                        turn.instructions,
                        turn.available,
                        cancellation,
                        run.sizingBegan);
                if (Set.of("transitioned", "stale").contains(continuation)) {
                    return Step.RESTART;
                }
            }
            var diagnostics = new LinkedHashMap<>(decision.diagnostics());
            diagnostics.put("continuation_outcome", continuation);
            diagnostics.put("source_estimated_input_tokens", decision.estimate().estimatedInputTokens());
            try {
                repository.update(id, snapshot.revision, s -> {
                    preparer.requireCurrentBoundary(s, cancellation);
                    s.contextDiagnostics = diagnostics;
                    s.partialReporting = PartialReporting
                            .pending(ContextPreparer.limitReason(decision), s.modelTurns + 1);
                    s.unusableResponses = 0;
                });
            } catch (ConflictException stale) {
                return Step.RESTART;
            }
            return Step.RESTART;
        }
        update(id, s -> s.contextDiagnostics = new LinkedHashMap<>(decision.diagnostics()));
        progress.stop(id, ExecutionStatus.partial, snapshot.partialReporting.reason());
        return Step.FINISHED;
    }

    /** Persists the model-turn intent and usage reservation before the provider is called. */
    private Step startModelRequest(Run run, Turn turn) throws Exception {
        String id = run.id;
        CancellationHandle cancellation = run.cancellation;
        ExecutionSnapshot snapshot = run.snapshot;
        var decision = turn.decision;
        var context = decision.messages();
        // The request may generate no more than the output allowance reserved for it below.
        long outputAllowance = progress.outputAllowance(snapshot);
        turn.request = new InferenceRequest(
                context,
                turn.instructions,
                InferenceOptions.defaults().withMaxOutputTokens(outputAllowance));
        turn.selectedModel = modelFor(snapshot);
        preparer.requireRemainingTime(snapshot, run.sizingBegan);
        turn.start = System.nanoTime();
        long modelReserve = ContextSizingPolicy
                .reserve(decision.estimate().estimatedInputTokens(), outputAllowance);
        if (childRepository != null) {
            childRepository.modelIntent("model:" + (snapshot.modelTurns + 1), modelReserve);
        }
        ExecutionSnapshot started;
        try {
            started = repository.update(id, childRepository == null ? snapshot.revision : -1, s -> {
                if (childRepository == null) {
                    preparer.requireCurrentBoundary(s, cancellation);
                }
                s.modelTurns++;
                if (s.partialReporting != null) {
                    s.partialReporting = s.partialReporting.markAttempted();
                }
                if (childRepository == null) {
                    s.contextDiagnostics = new LinkedHashMap<>(decision.diagnostics());
                }
                retainPreparedRequest(s, context);
                String source = "model:" + s.modelTurns;
                if (childRepository == null) {
                    s.providerReservations.put(source, modelReserve);
                }
                UsageAccounting.observe(s, source, null, true);
            });
        } catch (ConflictException stale) {
            return Step.RESTART;
        }
        turn.usageSource = "model:" + started.modelTurns;
        turn.modelTurn = started.modelTurns;
        run.providerIntent = turn.usageSource;
        String activity = childRepository != null
                ? "delegated"
                : started.monitoring != null
                        ? "monitoring"
                        : "scheduler".equals(started.scope.origin()) ? "scheduled" : "task";
        turn.usageContext = new UsageLedger.Context(
                started.scope.ownerAccountId(),
                started.executionId,
                started.executionId,
                activity,
                started.executionId,
                childRepository == null
                        ? turn.usageSource
                        : "delegated:" + childRepository.childId + ":" + turn.usageSource);
        return Step.ADVANCE;
    }

    /** Calls the provider and records receipt; malformed responses are counted as unusable. */
    private Step awaitModelResponse(Run run, Turn turn) throws Exception {
        String id = run.id;
        CancellationHandle cancellation = run.cancellation;
        ExecutionSnapshot snapshot = run.snapshot;
        var selectedModel = turn.selectedModel;
        var request = turn.request;
        var available = turn.available;
        var usageContext = turn.usageContext;
        var usageSource = turn.usageSource;
        var live = events;
        int modelTurn = turn.modelTurn;
        java.util.function.Consumer<String> textDelta = live == null || childRepository != null
                ? null
                : delta -> live.text(id, modelTurn, delta);
        clearLiveText(id);
        try {
            turn.response = cancellation.await(
                    operations,
                    () -> BoundedInference.infer(
                            selectedModel,
                            request,
                            available,
                            cancellation,
                            usageContext,
                            (usage, missing) -> progress
                                    .recordUsage(id, usageSource, usage, missing),
                            textDelta),
                    Math.min(
                            progress.remaining(repository.get(id)),
                            preparer.requireRemainingTime(snapshot, run.sizingBegan)));
        } catch (IllegalArgumentException malformed) {
            clearLiveText(id);
            if (snapshot.partialReporting != null) {
                progress.stop(id, ExecutionStatus.partial, snapshot.partialReporting.reason());
                return Step.FINISHED;
            }
            if (childRepository != null) {
                childRepository.responseSaved();
            }
            if (unusable(id, "invalid_provider_response")) {
                return Step.FINISHED;
            }
            return Step.RESTART;
        } catch (InferenceResponseException rejected) {
            clearLiveText(id);
            if (snapshot.partialReporting != null) {
                progress.stop(id, ExecutionStatus.partial, snapshot.partialReporting.reason());
                return Step.FINISHED;
            }
            if (!rejected.malformed()) {
                throw rejected;
            }
            if (childRepository != null) {
                childRepository.responseSaved();
            }
            if (unusable(id, "invalid_provider_response")) {
                return Step.FINISHED;
            }
            return Step.RESTART;
        } catch (TransientInferenceException transientFailure) {
            clearLiveText(id);
            // Children report failure to their parent, and the single reporting turn is never repeated.
            if (childRepository != null || snapshot.partialReporting != null) {
                throw transientFailure;
            }
            return retryTransientFailure(run, transientFailure.code(), transientFailure.retryAfterMillis());
        }
        run.transientFailures = 0;
        progress.checkpoint(id, run.previous, run.began);
        run.providerIntent = null;
        log.info(
                "executionId={} stage=model modelTurn={} elapsedMs={} outcome=received",
                id,
                repository.get(id).modelTurns,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - turn.start));
        if (consume(id, cancellation)) {
            return Step.FINISHED;
        }
        return Step.ADVANCE;
    }

    /** Validates the response and commits it, with its calls, to the conversation. */
    private Step acceptResponse(Run run, Turn turn) {
        String id = run.id;
        CancellationHandle cancellation = run.cancellation;
        ExecutionSnapshot snapshot = run.snapshot;
        var response = turn.response;
        List<AcceptedCall> accepted;
        try {
            accepted = accept(repository.get(id), response);
        } catch (IllegalArgumentException invalid) {
            if (snapshot.partialReporting != null) {
                progress.stop(id, ExecutionStatus.partial, snapshot.partialReporting.reason());
                return Step.FINISHED;
            }
            if (childRepository != null) {
                if (response != null) {
                    repository.artifact(id, DelegationService.encode(response.toMessage()));
                }
                childRepository.responseSaved();
            }
            if (unusable(id, "unusable_response")) {
                return Step.FINISHED;
            } else {
                return Step.RESTART;
            }
        }
        turn.accepted = accepted;
        // A correction arriving during inference supersedes this candidate before acceptance.
        if (repository.get(id).corrections.size() != snapshot.corrections.size()) {
            return Step.RESTART;
        }
        try {
            update(id, s -> {
                if (s.cancellationRequested) {
                    return;
                }
                s.messages.add(response.toMessage());
                s.calls.addAll(accepted);
                if (!turn.reporting) {
                    // In a reporting turn the counter marks the single permitted retry of a rejected report.
                    s.unusableResponses = 0;
                }
                if (childRepository != null && accepted.isEmpty()) {
                    s.finalAnswer = response.text();
                    s.status = progress.budget(s).recordedTokensExhausted(s)
                            ? ExecutionStatus.partial
                            : ExecutionStatus.succeeded;
                }
            });
        } catch (RuntimeException cannotSave) {
            if (childRepository == null) {
                throw cannotSave;
            }
            repository.artifact(id, DelegationService.encode(response.toMessage()));
            progress.stop(id, ExecutionStatus.partial, "child_state_limit");
            return Step.FINISHED;
        }
        // The saved message now carries the text that was streamed live.
        clearLiveText(id);
        if (consume(id, cancellation)) {
            return Step.FINISHED;
        }
        return Step.ADVANCE;
    }

    /** Routes an accepted response: tool calls go back to the loop, a final answer is recorded. */
    private Step settleAcceptedResponse(Run run, Turn turn) {
        String id = run.id;
        ExecutionSnapshot snapshot = run.snapshot;
        var response = turn.response;
        boolean reporting = turn.reporting;
        if (!turn.accepted.isEmpty()) {
            if (reporting && childRepository == null) {
                toolCalls.settleUndispatched(id, "reporting_tools_disabled");
                return rejectReportingResponse(run, turn);
            }
            if (reporting || progress.budget(snapshot)
                    .tokensExhausted(snapshot, () -> progress.charged(repository.get(id)))) {
                toolCalls.settleUndispatched(id, "budget_limit");
                progress.stop(id, ExecutionStatus.partial, "tool_budget_limit");
                return Step.FINISHED;
            }
            return Step.RESTART;
        }
        if (reporting && childRepository == null && RawToolCallMarkup.present(response.text())) {
            return rejectReportingResponse(run, turn);
        }
        if (skillSelfCheck && childRepository == null
                && SkillSelfCheck
                        .applies(repository.get(id), true, reporting, progress.remaining(snapshot))) {
            // One extra turn so a loaded skill can be patched; the answer above stays the delivered one.
            update(id, s -> {
                if (!s.cancellationRequested && !SkillSelfCheck.performed(s)) {
                    s.messages.add(InferenceMessage.user(SkillSelfCheck.NOTICE));
                }
            });
            log.info("executionId={} stage=skill_self_check outcome=requested", id);
            return Step.RESTART;
        }
        // Recheck durable inbox inside the final-state lock: late input must not disappear.
        update(id, s -> {
            if (s.cancellationRequested || repository.inbox(id).stream()
                    .anyMatch(c -> !s.consumedInboxIds.contains(c.id()))) {
                return;
            }
            // After a skill self-check the delivered answer is the one given before the check.
            String answer = Objects.requireNonNullElse(SkillSelfCheck.candidateAnswer(s), response.text());
            var report = childRepository == null && s.monitoring == null
                    && s.purpose == TaskPurpose.generic
                            ? TaskOutcomeReport
                                    .parse(answer, s.goalGeneration, Instant.now())
                            : new TaskOutcomeReport.Parsed(answer, null);
            s.finalAnswer = report.answer();
            s.taskOutcome = report.outcome();
            s.status = childRepository == null && reporting || progress.budget(s).recordedTokensExhausted(s)
                    ? ExecutionStatus.partial
                    : ExecutionStatus.succeeded;
            if (s.partialReporting != null) {
                s.failure = s.partialReporting.reason();
                if (s.status == ExecutionStatus.blocked && !s.blockers.contains(s.failure)) {
                    s.blockers.add(s.failure);
                }
            }
        });
        var answered = repository.get(id);
        if (answered.status.terminal()) {
            AgentMetrics.taskStopped(answered.status, childRepository == null);
            return Step.FINISHED;
        }
        return Step.RESTART;
    }

    /**
     * The tools-disabled reporting response proposed tool calls or wrote tool-call markup instead of a
     * report, so it cannot be delivered as the final answer. The first such response is kept in the
     * conversation and answered with a notice, and the loop restarts for one further reporting request;
     * the second ends the task as partial without a report. The response has been committed, so the
     * retry is a new request, never a replay of an uncertain one.
     */
    private Step rejectReportingResponse(Run run, Turn turn) {
        String id = run.id;
        ExecutionSnapshot snapshot = run.snapshot;
        repository.diagnostic(id, "reporting_response_unusable", turn.response.text());
        if (snapshot.unusableResponses > 0) {
            update(id, s -> {
                if (s.finalAnswer == null) {
                    s.finalAnswer = "No final report was produced: the model answered the tools-disabled "
                            + "reporting turn with tool calls twice. Saved evidence and tool artifacts remain "
                            + "available.";
                }
            });
            log.info("executionId={} stage=reporting outcome=unusable_response_stop", id);
            progress.stop(id, ExecutionStatus.partial, "reporting_response_unusable");
            return Step.FINISHED;
        }
        update(id, s -> {
            if (s.cancellationRequested) {
                return;
            }
            s.unusableResponses = 1;
            s.messages.add(InferenceMessage.user(SystemPrompts.REPORTING_RESPONSE_REJECTED_NOTICE));
        });
        log.info("executionId={} stage=reporting outcome=unusable_response_retry", id);
        return Step.RESTART;
    }

    /**
     * Decides whether the whole prepared request for the model turn being started is persisted in
     * {@code preparedRequests}, keyed by the new turn number. Only root runs retain it, and only when
     * it differs from the saved conversation messages (for example after compaction, hook context, or
     * a cloud handoff). The stored request is the exact transmitted view: the codec rejects any later
     * change to an entry as an immutable-context violation. Runs inside the model-turn start update,
     * after the turn counter is incremented. Change the retention policy here, not in the run loop.
     */
    private void retainPreparedRequest(ExecutionSnapshot state, List<InferenceMessage> prepared) {
        if (childRepository == null && !prepared.equals(state.messages)) {
            state.preparedRequests.put(state.modelTurns, prepared);
        }
    }

    private List<AcceptedCall> accept(ExecutionSnapshot snapshot, InferenceResponse response) {
        if (response == null || response.content().isEmpty() || response.stopReason() == null
                || !Set.of("end_turn", "stop_sequence", "tool_use", "completed", "stop")
                        .contains(response.stopReason())) {
            throw new IllegalArgumentException("Incomplete response");
        }
        var result = new ArrayList<AcceptedCall>();
        var ids = new HashSet<String>();
        snapshot.calls.forEach(c -> ids.add(c.id));
        for (var block : response.content()) {
            var interaction = InferenceToolCalls.decode(block);
            if (interaction instanceof InferenceToolCalls.UnsupportedAction unsupported
                    && unsupported.call()) {
                throw new IllegalArgumentException("Unsupported native call");
            }
            if (interaction instanceof InferenceToolCalls.Call decoded) {
                if (InferenceToolCalls.registerIdentity(decoded.id(), ids) != null) {
                    throw new IllegalArgumentException("Duplicate or empty call ID");
                }
                var call = toolCalls.accept(snapshot, decoded);
                call.goalGeneration = snapshot.goalGeneration;
                call.modelTurn = snapshot.modelTurns;
                call.state = CallState.accepted;
                result.add(call);
            }
        }
        if (result.size() > 16 || result.isEmpty() && response.text().isBlank()) {
            throw new IllegalArgumentException("Unusable response");
        }
        return result;
    }

    private boolean consume(String id, CancellationHandle cancellation) {
        var snapshot = repository.get(id);
        if (snapshot.cancellationRequested || cancellation.cancelled()) {
            toolCalls.settleUndispatched(id, "cancelled");
            toolCalls.settleDispatched(id, "uncertain");
            progress.stop(id, ExecutionStatus.cancelled, "cancelled");
            return true;
        }
        if (childRepository != null) {
            if (!childRepository.current()) {
                toolCalls.settleUndispatched(id, "superseded");
                toolCalls.settleDispatched(id, "uncertain");
                progress.stop(id, ExecutionStatus.partial, "superseded");
                return true;
            }
            return false;
        }
        var corrections = repository.inbox(id).stream()
                .filter(c -> !snapshot.consumedInboxIds.contains(c.id())).toList();
        if (!corrections.isEmpty()) {
            toolCalls.settleUndispatched(id, "superseded");
            update(id, s -> {
                for (var correction : corrections) {
                    if (s.consumedInboxIds.add(correction.id())) {
                        // A durable inbox append can survive a failed admission snapshot write.
                        if (s.goalGenerationInboxIds.add(correction.id())) {
                            s.goalGeneration++;
                        }
                        s.outputReports.clear();
                        s.taskOutcome = null;
                        s.corrections.add(correction);
                        s.currentGoal = correction.text();
                        if (correction.sourceMessages().isEmpty()) {
                            s.messages.add(InferenceMessage.user(correction.text()));
                        } else {
                            SourceContext.append(s, correction.sourceMessages());
                        }
                        if (correction.sourceTurn() != null) {
                            s.acceptedSourceTurns.add(correction.sourceTurn());
                        }
                    }
                }
                s.pendingQuestions.clear();
            });
        }
        return false;
    }

    private boolean unusable(String id, String code) {
        repository.diagnostic(id, code, code);
        var snapshot = update(id, s -> s.unusableResponses++);
        if (progress.budget(snapshot)
                .unusableResponsesExhausted(
                        snapshot,
                        () -> childRepository != null && childRepository.child().repairAttempts > 0)) {
            progress.stop(id, ExecutionStatus.failed, "consecutive_unusable_responses");
            return true;
        }
        return false;
    }

    /** Unattended monitoring questions settle locally even when scheduler polling is disabled. */
    public boolean settleMonitoringInput(String id) {
        var snapshot = repository.get(id);
        if (snapshot.monitoring == null || snapshot.status.terminal() || snapshot.pendingQuestions.isEmpty()
                || snapshot.cancellationRequested) {
            return false;
        }
        toolCalls.settleUndispatched(id, "monitoring_owner_input_required");
        var settled = update(id, s -> {
            if (!s.cancellationRequested && !s.pendingQuestions.isEmpty()) {
                s.status = ExecutionStatus.blocked;
                s.failure = "Monitoring requires owner input";
                if (!s.blockers.contains("monitoring_owner_input_required")) {
                    s.blockers.add("monitoring_owner_input_required");
                }
            }
        });
        return settled.status.terminal();
    }

    private void waiting(String id) {
        if (settleMonitoringInput(id)) {
            return;
        }
        update(id, s -> {
            if (!s.cancellationRequested
                    && repository.inbox(id).stream().noneMatch(c -> !s.consumedInboxIds.contains(c.id()))) {
                s.status = ExecutionStatus.waiting_for_user;
            }
        });
    }

    private ExecutionSnapshot update(String id, Consumer<ExecutionSnapshot> update) {
        return repository.update(id, -1, update);
    }

    private static final class ProfileUnavailableException extends IllegalStateException {

        ProfileUnavailableException(String reason) {
            super(reason);
        }
    }

    private ModelClient modelFor(ExecutionSnapshot snapshot) {
        if (childRepository != null) {
            return model;
        }
        var binding = Objects
                .requireNonNull(snapshot.activeInferenceBinding(), "Inference binding is required");
        var resolution = PinnedInference.resolve(binding, profiles);
        return switch (resolution.status()) {
            case configured -> new InferenceModelClient(resolution.profile().service(), modelStreaming);
            case unavailable -> throw new ProfileUnavailableException("inference_profile_unavailable");
            case incompatible -> throw new ProfileUnavailableException("inference_profile_incompatible");
        };
    }

    @Override
    public void close() {
        operations.shutdownNow();
    }
}
