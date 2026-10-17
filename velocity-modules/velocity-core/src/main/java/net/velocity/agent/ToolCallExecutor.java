package net.velocity.agent;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.json.JsonMapper;

import net.velocity.agent.persistence.ExecutionRepository;
import net.velocity.agent.persistence.ExecutionStateCodec;
import net.velocity.agent.state.*;
import net.velocity.agent.tools.DelegateWorkTool;
import net.velocity.inference.SystemPrompts;
import net.velocity.inference.objects.*;

/** Executes accepted command data through current trusted handlers and durable receipts. */
final class ToolCallExecutor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ToolCallExecutor.class);
    private static final JsonMapper JSON = new JsonMapper();
    private final ExecutionRepository repository;
    private final ChildRunRepository childRepository;
    private final ToolRegistry tools;
    private final AuthorizationPolicy authorization;
    private final ExecutionProgress progress;
    private final DelegationService delegation;
    private final List<String> childToolNames;
    private final CallSettlement settlement;
    private final ToolCallRecovery recovery;
    private volatile Consumer<ExecutionSnapshot> monitoringValidator;
    private volatile Consumer<ExecutionSnapshot> scheduledValidator;
    private volatile ToolApprovalPolicy approval = ToolApprovalPolicy.disabled();
    private final ExecutorService operations = Executors.newVirtualThreadPerTaskExecutor();

    ToolCallExecutor(
            ExecutionRepository repository,
            ToolRegistry tools,
            AuthorizationPolicy authorization,
            ExecutionProgress progress,
            DelegationService delegation) {
        this.repository = repository;
        this.tools = tools;
        this.authorization = authorization;
        this.progress = progress;
        this.delegation = delegation;
        this.childRepository = repository instanceof ChildRunRepository child ? child : null;
        this.childToolNames = childRepository == null
                ? List.of()
                : childRepository.childProfileToolNames(
                        childRepository.child().profile,
                        childRepository.child().profileVersion);
        this.settlement = new CallSettlement(repository, tools);
        this.recovery = new ToolCallRecovery(
                repository,
                tools,
                delegation,
                childToolNames,
                settlement,
                progress,
                this::executionScope);
    }

    public void setScheduledValidator(Consumer<ExecutionSnapshot> validator) {
        scheduledValidator = Objects.requireNonNull(validator);
        if (delegation != null) {
            delegation.setScheduledValidator(validator);
        }
    }

    public void setMonitoringValidator(Consumer<ExecutionSnapshot> validator) {
        monitoringValidator = Objects.requireNonNull(validator);
    }

    void setApprovalPolicy(ToolApprovalPolicy policy) {
        approval = Objects.requireNonNull(policy);
    }

    /** True while any accepted call waits for the requester's decision. */
    static boolean awaitingApproval(ExecutionSnapshot snapshot) {
        return CallSettlement.awaitingApproval(snapshot);
    }

    private enum ApprovalHold {
        /** Dispatch the call. */
        NONE,
        /** The call now waits for the requester; stop processing the queue. */
        WAIT,
        /** The call settled without running; continue with the next one. */
        SETTLED
    }

    /**
     * Applies the approval policy to one eligible call. The decision is recorded on the call itself, so a
     * crash between the hold and the wait status is recovered by reading the queue again. Unattended work
     * (monitoring runs and delegated children) cannot obtain approval and settles the call as denied; a
     * tool held only because it confirms with the requester runs unattended instead, including in
     * scheduled occurrences, since the delivered result reports what it did.
     */
    private ApprovalHold approvalHold(String id, AcceptedCall candidate) {
        var snapshot = repository.get(id);
        var call = CallSettlement.call(snapshot, candidate.id);
        if (call.state != CallState.accepted) {
            return ApprovalHold.SETTLED;
        }
        if (call.approval == null) {
            var definition = tools.find(Objects.toString(call.name, ""));
            if (definition.isEmpty() || !approval.holds(definition.get())) {
                return ApprovalHold.NONE;
            }
            // A confirming tool pauses only for a present requester; the policy's own holds are stricter.
            boolean confirmationOnly = !approval.requiresApproval(definition.get());
            if (childRepository != null || snapshot.monitoring != null) {
                if (confirmationOnly) {
                    return ApprovalHold.NONE;
                }
                settlement.settle(
                        id,
                        call,
                        true,
                        "denied",
                        SystemPrompts.TOOL_APPROVAL_UNATTENDED_RESULT,
                        null);
                return ApprovalHold.SETTLED;
            }
            if (confirmationOnly && "scheduler".equals(snapshot.scope.origin())) {
                return ApprovalHold.NONE;
            }
            String summary = preview(id, snapshot, definition.get(), call);
            update(id, s -> {
                var held = CallSettlement.call(s, call.id);
                if (held.state == CallState.accepted && held.approval == null) {
                    held.approval = CallApproval.pending(Instant.now(), summary);
                }
            });
            log.info("executionId={} stage=approval tool={} outcome=requested", id, call.name);
            return ApprovalHold.WAIT;
        }
        return switch (call.approval.state()) {
            case pending -> ApprovalHold.WAIT;
            case approved -> ApprovalHold.NONE;
            case declined -> {
                settlement.settle(
                        id,
                        call,
                        true,
                        "declined",
                        SystemPrompts.TOOL_APPROVAL_DECLINED_RESULT,
                        null);
                log.info("executionId={} stage=approval tool={} outcome=declined", id, call.name);
                yield ApprovalHold.SETTLED;
            }
        };
    }

    /**
     * The trusted plain-language description of a held call, or null when the tool offers none or its
     * preview fails. The preview runs with inert reporters and no deadline, like reconciliation, and may
     * only read.
     */
    private String preview(
            String id,
            ExecutionSnapshot snapshot,
            ToolRegistry.Definition definition,
            AcceptedCall call) {
        if (!(definition.handler() instanceof ToolRegistry.ConfirmingHandler confirming)) {
            return null;
        }
        try {
            String text = confirming.preview(
                    toolContext(
                            id,
                            snapshot.scope,
                            new CancellationHandle(),
                            ignored -> {},
                            (source, usage, missing) -> {},
                            call,
                            null,
                            ignored -> {},
                            Long.MAX_VALUE),
                    call.input);
            return text == null || text.isBlank() ? null : text;
        } catch (Exception failure) {
            log.warn(
                    "executionId={} stage=approval tool={} outcome=preview_failed",
                    id,
                    call.name,
                    failure);
            return null;
        }
    }

    public void processAcceptedCalls(String id, CancellationHandle cancellation, long previous, long began)
            throws Exception {
        ExecutionSnapshot snapshot;
        // Recovery has priority over any new dispatch.
        recoverSettlements(id);
        if (repository.get(id).status != ExecutionStatus.running) {
            return;
        }
        // Only the trusted delegation primitive may resume a dispatched call without a receipt.
        if (childRepository == null) {
            for (var interrupted : repository.get(id).calls) {
                if (interrupted.state != CallState.dispatched || !settlement.delegationCall(interrupted)) {
                    continue;
                }
                Outcome outcome = invoke(id, interrupted, cancellation, previous, began);
                publishOutcome(id, interrupted, outcome);
                if (outcome.uncertain) {
                    settleUndispatched(id, "destination_inspection_required");
                    progress.stop(id, ExecutionStatus.blocked, "destination_inspection_required");
                    return;
                }
            }
        }
        if (!repository.get(id).pendingQuestions.isEmpty()) {
            return;
        }
        List<AcceptedCall> pending = repository.get(id).calls.stream()
                .filter(c -> c.state == CallState.accepted).toList();
        for (int index = 0; index < pending.size();) {
            if (inputPending(id, cancellation)) {
                return;
            }
            progress.checkpoint(id, previous, began);
            var group = new ArrayList<AcceptedCall>();
            var first = pending.get(index++);
            if (!eligible(id, first)) {
                continue;
            }
            // Calls are held in queue order: a held call stops the queue until the requester decides.
            ApprovalHold hold = approvalHold(id, first);
            if (hold == ApprovalHold.WAIT) {
                return;
            }
            if (hold == ApprovalHold.SETTLED) {
                continue;
            }
            group.add(first);
            if (childRepository == null && tools.find(first.name).orElseThrow().parallelRead()) {
                while (index < pending.size() && group.size() < 4) {
                    var next = pending.get(index);
                    var definition = tools.find(next.name);
                    if (definition.isEmpty() || !definition.get().parallelRead()) {
                        break;
                    }
                    index++;
                    if (eligible(id, next)) {
                        group.add(next);
                    }
                }
            }
            if (inputPending(id, cancellation)) {
                return;
            }
            snapshot = repository.get(id);
            if (progress.budget(snapshot)
                    .toolCallsExceeded(snapshot, progress.toolDispatches(snapshot), group.size())
                    || progress.remaining(snapshot) <= 0) {
                settleUndispatched(id, "budget_limit");
                return;
            }
            // Earlier groups of this response may have crossed the task token ceiling (for example a
            // delegation that spent tokens); later groups settle without running. The run loop then
            // stops the task at its next iteration.
            var ceilingSnapshot = snapshot;
            if (progress.budget(snapshot)
                    .tokenCeilingReached(snapshot, () -> progress.taskCharged(ceilingSnapshot))) {
                settleUndispatched(id, "task_token_ceiling");
                return;
            }
            var dispatched = new ArrayList<AcceptedCall>();
            try {
                update(id, s -> {
                    if (s.cancellationRequested || repository.inbox(id).stream()
                            .anyMatch(c -> !s.consumedInboxIds.contains(c.id()))) {
                        return;
                    }
                    for (var candidate : group) {
                        var call = CallSettlement.call(s, candidate.id);
                        if (call.state == CallState.accepted) {
                            call.state = CallState.dispatched;
                            call.dispatchedAt = Instant.now();
                            s.toolCalls++;
                            dispatched.add(call);
                        }
                    }
                });
            } catch (ChildRunRepository.CapacityException exhausted) {
                if (childRepository == null) {
                    throw exhausted;
                }
                settleUndispatched(id, exhausted.getMessage());
                progress.stop(id, ExecutionStatus.partial, exhausted.getMessage());
                return;
            }
            // Corrections after checkpoint settle these calls without dispatch; persisted dispatch is conservative on crash.
            var current = repository.get(id);
            if (repository.inbox(id).stream().anyMatch(c -> !current.consumedInboxIds.contains(c.id()))) {
                for (var call : dispatched) {
                    settlement.settle(id, call, true, "superseded", "Call superseded before dispatch", null);
                }
                return;
            }
            if (inputPending(id, cancellation)) {
                return;
            }
            var results = new ArrayList<Future<Outcome>>();
            for (var call : dispatched) {
                results.add(operations.submit(() -> invoke(id, call, cancellation, previous, began)));
            }
            for (int n = 0; n < dispatched.size(); n++) {
                Outcome outcome = results.get(n).get();
                var call = dispatched.get(n);
                String publishedCode = publishOutcome(id, call, outcome);
                log.info(
                        "executionId={} stage=tool modelTurn={} elapsedMs={} outcome={}",
                        id,
                        repository.get(id).modelTurns,
                        java.time.Duration.between(call.dispatchedAt, Instant.now()).toMillis(),
                        publishedCode);
                if (outcome.uncertain) {
                    settleUndispatched(id, "destination_inspection_required");
                    progress.stop(id, ExecutionStatus.blocked, "destination_inspection_required");
                    // All already-dispatched read siblings still receive their matching result below.
                }
            }
            progress.checkpoint(id, previous, began);
            if (!cancellation.settledForCaller()) {
                if (repository.get(id).status != ExecutionStatus.running) {
                    return;
                }
                if (progress.awaitSettlement(id, cancellation, previous, began)) {
                    continue;
                }
                settleUndispatched(id, "operation_not_settled");
                progress.stop(id, ExecutionStatus.blocked, "operation_not_settled");
                return;
            }
            if (repository.get(id).status != ExecutionStatus.running) {
                return;
            }
            if (!repository.get(id).pendingQuestions.isEmpty()) {
                return;
            }
        }
    }

    private boolean eligible(String id, AcceptedCall candidate) {
        var snapshot = repository.get(id);
        var call = CallSettlement.call(snapshot, candidate.id);
        if (call.state != CallState.accepted) {
            return false;
        }
        if (childRepository != null && !childToolNames.contains(call.name)) {
            settlement
                    .settle(id, call, true, "denied", "Tool is outside the child's capability profile", null);
            return false;
        }
        var definition = tools.find(Objects.toString(call.name, ""));
        if (call.inputError != null || definition.isEmpty()) {
            settlement.settle(id, call, true, "invalid_input", "Unknown tool or malformed input", null);
            return false;
        }
        try {
            ToolCallContracts.validateInput(definition.get(), call);
        } catch (IllegalArgumentException e) {
            settlement.settle(id, call, true, "invalid_input", "Tool input failed schema validation", null);
            return false;
        }
        if (!call.binding.equals(settlement.binding(snapshot, call))) {
            settlement.settle(id, call, true, "binding_changed", "Tool binding changed", null);
            return false;
        }
        if (snapshot.monitoring != null && !definition.get().monitoringAllowed(call.input)) {
            settlement.settle(
                    id,
                    call,
                    true,
                    "denied",
                    "Monitoring runs authorize observation only; this action is unavailable",
                    null);
            return false;
        }
        if (childRepository == null && snapshot.admissionContext != null
                && snapshot.admissionContext.tools().stream().noneMatch(
                        pinned -> pinned.name().equals(call.name)
                                && pinned.compatible(definition.get()))) {
            settlement.settle(
                    id,
                    call,
                    true,
                    "binding_changed",
                    "Tool is absent or incompatible with admitted contract",
                    null);
            return false;
        }
        var decision = authorization.authorize(snapshot.scope, definition.get(), call);
        if (decision != AuthorizationPolicy.Decision.allow) {
            settlement.settle(id, call, true, "denied", "Tool permission denied", null);
            return false;
        }
        try {
            executionScope(snapshot);
        } catch (RuntimeException unavailable) {
            settlement.settle(
                    id,
                    call,
                    true,
                    "denied",
                    "Execution account or saved job association could not authorize this call",
                    null);
            return false;
        }
        return true;
    }

    private TrustedScope executionScope(ExecutionSnapshot snapshot) {
        snapshot.scope.requireOwnerAccountId();
        if ("scheduler".equals(snapshot.scope.origin())) {
            var validator = scheduledValidator;
            if (validator == null) {
                throw new SecurityException("Schedule association verifier unavailable");
            }
            validator.accept(snapshot);
        }
        if (snapshot.monitoring != null) {
            var validator = monitoringValidator;
            if (validator == null) {
                throw new SecurityException("Monitoring association verifier unavailable");
            }
            validator.accept(snapshot);
        }
        return snapshot.scope;
    }

    /**
     * Runs one dispatched call: derive its deadline, execute the trusted handler (or delegation),
     * then classify the result as an {@link Outcome}. Persisting the receipt and settlement is the
     * caller's job ({@link #publishOutcome}).
     */
    private Outcome invoke(
            String id,
            AcceptedCall call,
            CancellationHandle cancellation,
            long previous,
            long began) {
        var snapshot = repository.get(id);
        var definition = tools.find(call.name).orElseThrow();
        long timeout = invocationTimeoutMillis(snapshot, definition, previous, began);
        if (timeout <= 0) {
            return timeLimitOutcome(id, call);
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1, timeout));
        for (int attempt = 0;; attempt++) {
            try {
                cancellation.check();
                Object output = cancellation.await(
                        operations,
                        () -> executeHandler(id, call, definition, snapshot, cancellation, deadline),
                        TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                return completedOutcome(id, call, definition, snapshot, output);
            } catch (ChildRunRepository.CapacityException stopped) {
                return new Outcome(
                        true,
                        "not_dispatched",
                        "Child work stopped: " + stopped.getMessage(),
                        null,
                        false,
                        false);
            } catch (ToolRegistry.UncertainToolException e) {
                return uncertainToolOutcome(definition, snapshot, e);
            } catch (ToolRegistry.TransientReadException e) {
                if (!definition.read()) {
                    return new Outcome(
                            true,
                            "uncertain",
                            "Mutation outcome unknown; destination inspection required",
                            null,
                            false,
                            true);
                }
                if (attempt == 0 && definition.parallelRead()
                        && deadline - System.nanoTime() > TimeUnit.MILLISECONDS.toNanos(250)) {
                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    continue;
                }
                return new Outcome(true, "read_failed", "Transient read failed", null, false, false);
            } catch (Exception e) {
                boolean uncertain = !definition.read() && !"agent.question".equals(definition.category());
                return new Outcome(
                        true,
                        uncertain ? "uncertain" : "tool_failed",
                        uncertain
                                ? "External outcome unknown; destination inspection required before another mutation"
                                : "Tool did not complete",
                        null,
                        false,
                        uncertain);
            }
        }
    }

    /** The smallest of the remaining task time, the tool timeout, and the active-time limit. */
    private long invocationTimeoutMillis(
            ExecutionSnapshot snapshot,
            ToolRegistry.Definition definition,
            long previous,
            long began) {
        var budget = progress.budget(snapshot);
        long timeout = budget
                .capToolTimeout(snapshot, definition.timeoutMillis(), progress.remaining(snapshot));
        if (budget.deductsElapsedTime(childRepository == null)) {
            timeout = Math.min(
                    timeout,
                    snapshot.limits.activeMillis() - previous
                            - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began));
        }
        return timeout;
    }

    /** No time remains: stop a delegation wrapper at its limit, or refuse the dispatch. */
    private Outcome timeLimitOutcome(String id, AcceptedCall call) {
        if (settlement.delegationCall(call)) {
            Object result = delegation.stopAtTimeLimit(id, call);
            String raw = DelegationService.encode(result);
            boolean uncertain = repository.get(id).blockers.contains("destination_inspection_required");
            String status = result instanceof Map<?, ?> envelope
                    ? Objects.toString(envelope.get("status"), "failed")
                    : "failed";
            return new Outcome(
                    !Set.of("complete", "partial", "needs_input").contains(status),
                    uncertain ? "uncertain" : "superseded".equals(status) ? "superseded" : "confirmed",
                    raw,
                    raw,
                    false,
                    uncertain);
        }
        return new Outcome(
                true,
                "budget_limit",
                "No time remains for another tool dispatch",
                null,
                false,
                false);
    }

    /** Runs on the operations executor: the delegation wrapper or the registered tool handler. */
    private Object executeHandler(
            String id,
            AcceptedCall call,
            ToolRegistry.Definition definition,
            ExecutionSnapshot snapshot,
            CancellationHandle cancellation,
            long deadline) throws Exception {
        if (settlement.delegationCall(call)) {
            return delegation.execute(id, call, cancellation, deadline, true);
        }
        if (childRepository != null && !childRepository.current()) {
            throw new ChildRunRepository.CapacityException("superseded");
        }
        // Handlers run on their own virtual thread, which does not inherit the worker's logging context.
        org.slf4j.MDC.put("executionId", id);
        org.slf4j.MDC.put("tool", call.name);
        try {
            return definition.handler().execute(
                    toolContext(
                            id,
                            executionScope(snapshot),
                            cancellation,
                            progressReporter(id, call),
                            usageReporter(id, call),
                            call,
                            childRepository == null ? null : childRepository.childId,
                            providerAdmission(id, call),
                            deadline),
                    call.input);
        } finally {
            org.slf4j.MDC.remove("tool");
            org.slf4j.MDC.remove("executionId");
        }
    }

    /**
     * The single place a handler's {@link ToolRegistry.Context} is built. Execution and
     * reconciliation both go through here; reconciliation passes inert reporters and no deadline.
     */
    static ToolRegistry.Context toolContext(
            String id,
            TrustedScope scope,
            CancellationHandle cancellation,
            Consumer<String> progressReporter,
            ToolRegistry.UsageReporter usageReporter,
            AcceptedCall call,
            String childRunId,
            ToolRegistry.ProviderRequestAdmission providerRequestAdmission,
            long operationDeadlineNanos) {
        return ToolRegistry.Context.builder()
                .executionId(id)
                .scope(scope)
                .cancellation(cancellation)
                .progressReporter(progressReporter)
                .usageReporter(usageReporter)
                .acceptedCall(call.id, call.binding)
                .childRunId(childRunId)
                .providerRequestAdmission(providerRequestAdmission)
                .operationDeadlineNanos(operationDeadlineNanos)
                .build();
    }

    private Consumer<String> progressReporter(String id, AcceptedCall call) {
        return text -> update(id, s -> {
            var active = CallSettlement.call(s, call.id);
            if (active.state == CallState.dispatched) {
                active.progress = progress(text);
            }
        });
    }

    private ToolRegistry.UsageReporter usageReporter(String id, AcceptedCall call) {
        return (source, usage, missing) -> {
            if (source == null || source.isBlank() || source.length() > 512) {
                throw new IllegalArgumentException("A stable delegated usage source ID is required");
            }
            progress.recordUsage(
                    id,
                    childRepository == null ? "delegated:" + source : "file:" + call.id + ":" + source,
                    usage,
                    missing);
        };
    }

    private ToolRegistry.ProviderRequestAdmission providerAdmission(String id, AcceptedCall call) {
        return new ToolRegistry.ProviderRequestAdmission() {

            @Override
            public void admit(String source) {
                admit(
                        source,
                        ContextSizingPolicy.reserve(2048, progress.outputAllowance(repository.get(id))));
            }

            @Override
            public void admit(String source, long reservation) {
                if (childRepository != null) {
                    childRepository.admitProviderRequest("file:" + call.id + ":" + source, reservation);
                    return;
                }
                update(id, s -> {
                    String usageSource = "delegated:" + source;
                    if (s.providerReservations.containsKey(usageSource)) {
                        return;
                    }
                    if (reservation > progress.remainingTokens(s)) {
                        throw new ToolRegistry.ProviderRequestLimitException(
                                "Task token budget prevents another file inference request");
                    }
                    s.providerReservations.put(usageSource, reservation);
                });
            }
        };
    }

    /** Encodes, validates and classifies a handler's output into the outcome to publish. */
    private Outcome completedOutcome(
            String id,
            AcceptedCall call,
            ToolRegistry.Definition definition,
            ExecutionSnapshot snapshot,
            Object output) {
        String raw;
        try {
            raw = JSON.writeValueAsString(output);
        } catch (Exception invalid) {
            return new Outcome(
                    true,
                    "invalid_output",
                    "Tool output could not be encoded; inspect the saved diagnostic artifact",
                    Objects.toString(output, "null"),
                    true,
                    !definition.read()).guardedBy(output);
        }
        try {
            JsonSchema.validate(definition.outputSchema(), output);
        } catch (IllegalArgumentException invalid) {
            return new Outcome(
                    true,
                    "invalid_output",
                    "Tool output failed validation; inspect the saved raw artifact",
                    raw,
                    true,
                    !definition.read()).guardedBy(output);
        }
        if (definition.handler() instanceof ToolRegistry.CompleteResultHandler
                && raw.length() > snapshot.limits.outputPreviewChars()) {
            return new Outcome(
                    true,
                    "result_capacity",
                    "Complete result exceeds this execution's output capacity; use a new task with sufficient limits.",
                    raw,
                    false,
                    false).guardedBy(output);
        }
        if (definition.handler() instanceof ToolRegistry.FailureAwareHandler handler
                && handler.failed(output)) {
            return new Outcome(
                    true,
                    "tool_failed",
                    CallSettlement.preview(raw, snapshot.limits.outputPreviewChars()),
                    raw,
                    false,
                    false).guardedBy(output);
        }
        if (settlement.delegationCall(call)) {
            boolean uncertain = repository.get(id).blockers.contains("destination_inspection_required");
            String status = output instanceof Map<?, ?> envelope
                    ? Objects.toString(envelope.get("status"), "failed")
                    : "failed";
            return new Outcome(
                    !Set.of("complete", "partial", "needs_input").contains(status),
                    uncertain ? "uncertain" : status.equals("superseded") ? "superseded" : "confirmed",
                    raw,
                    raw,
                    false,
                    uncertain);
        }
        return new Outcome(
                false,
                "confirmed",
                CallSettlement.preview(raw, snapshot.limits.outputPreviewChars()),
                raw,
                false,
                false).guardedBy(output);
    }

    /** A tool reported that its external outcome is unknown or that it did not complete. */
    private Outcome uncertainToolOutcome(
            ToolRegistry.Definition definition,
            ExecutionSnapshot snapshot,
            ToolRegistry.UncertainToolException e) {
        boolean uncertain = !definition.read() && !"agent.question".equals(definition.category());
        String raw;
        try {
            raw = JSON.writeValueAsString(
                    Map.of(
                            "status",
                            uncertain ? "uncertain" : "tool_failed",
                            "action_outcome",
                            uncertain ? "uncertain" : "none",
                            "content_trust",
                            "untrusted_data",
                            "message",
                            uncertain
                                    ? "External outcome unknown; destination inspection required before another mutation"
                                    : "Tool did not complete",
                            "diagnostic",
                            e.diagnostic()));
        } catch (Exception invalid) {
            return new Outcome(
                    true,
                    uncertain ? "uncertain" : "tool_failed",
                    "Tool did not complete",
                    null,
                    false,
                    uncertain);
        }
        return new Outcome(
                true,
                uncertain ? "uncertain" : "tool_failed",
                CallSettlement.preview(raw, snapshot.limits.outputPreviewChars()),
                raw,
                false,
                uncertain,
                "tool_failure");
    }

    private record Outcome(
            boolean error,
            String code,
            String preview,
            String raw,
            boolean invalid,
            boolean uncertain,
            String diagnosticCode,
            ToolRegistry.ReadPublicationGuard publicationGuard) {

        private Outcome(
                boolean error,
                String code,
                String preview,
                String raw,
                boolean invalid,
                boolean uncertain) {
            this(error, code, preview, raw, invalid, uncertain, null, null);
        }

        private Outcome(
                boolean error,
                String code,
                String preview,
                String raw,
                boolean invalid,
                boolean uncertain,
                String diagnosticCode) {
            this(error, code, preview, raw, invalid, uncertain, diagnosticCode, null);
        }

        private Outcome guardedBy(Object output) {
            return output instanceof ToolRegistry.GuardedReadResult result
                    ? new Outcome(
                            error,
                            code,
                            preview,
                            raw,
                            invalid,
                            uncertain,
                            diagnosticCode,
                            result.publicationGuard())
                    : this;
        }
    }

    private String publishOutcome(String id, AcceptedCall call, Outcome outcome) {
        AgentMetrics.toolCall(call.name, outcome.code, childRepository == null);
        if (call.dispatchedAt != null) {
            AgentMetrics.toolDuration(
                    call.name,
                    java.time.Duration.between(call.dispatchedAt, Instant.now()).toMillis());
        }
        if (outcome.publicationGuard != null) {
            boolean published = outcome.publicationGuard
                    .publish(() -> publishAuthorizedOutcome(id, call, outcome));
            if (!published) {
                settlement.settle(
                        id,
                        call,
                        true,
                        "connection_changed",
                        "Read result discarded because its connection changed",
                        null);
            }
            return published ? outcome.code : "connection_changed";
        }
        publishAuthorizedOutcome(id, call, outcome);
        return outcome.code;
    }

    private void publishAuthorizedOutcome(String id, AcceptedCall call, Outcome outcome) {
        if (settlement.delegationCall(call)) {
            var root = repository.get(id);
            if (root.cancellationRequested || root.goalGeneration != call.goalGeneration
                    || repository.inbox(id).stream().anyMatch(c -> !root.consumedInboxIds.contains(c.id()))) {
                String superseded = DelegationService.encode(
                        Map.of(
                                "status",
                                "superseded",
                                "content_trust",
                                "untrusted_data",
                                "summary",
                                "The child assignment is no longer current"));
                outcome = new Outcome(true, "superseded", superseded, superseded, false, outcome.uncertain);
            }
        }
        String artifact = outcome.raw == null ? null : repository.artifact(id, outcome.raw);
        if (outcome.invalid && outcome.raw != null) {
            repository.diagnostic(id, "invalid_output", outcome.raw);
        }
        if (outcome.diagnosticCode != null && outcome.raw != null) {
            repository.diagnostic(id, outcome.diagnosticCode, outcome.raw);
        }
        String result = outcome.preview;
        if (childRepository != null) {
            // Keep room for both durable receipt and native settlement; complete raw data is an owned artifact.
            int room = childRepository.child().maxStateBytes
                    - ExecutionStateCodec.encodedBytes(childRepository.child()) - 4096;
            if (result.getBytes(java.nio.charset.StandardCharsets.UTF_8).length * 6L > room) {
                result = "Output retained in artifact " + artifact + "; child state capacity reached";
            }
        }
        settlement.settle(id, call, outcome.error, outcome.code, result, artifact);
    }

    public void recoverSettlements(String id) {
        recovery.recoverSettlements(id);
    }

    public void settleUndispatched(String id, String outcome) {
        settlement.settleUndispatched(id, outcome);
    }

    public void settleDispatched(String id, String outcome) {
        settlement.settleDispatched(id, outcome);
    }

    /** Reconcile expired saved work without starting providers, child conversations, or tool handlers. */
    public void settleAtTimeLimit(String id) {
        settleAtBoundary(
                id,
                "active_time_limit",
                (rootId, wrapper) -> delegation.stopAtTimeLimit(rootId, wrapper));
    }

    /** The same reconciliation at the task token ceiling; child results name the ceiling, not the deadline. */
    void settleAtTokenCeiling(String id) {
        settleAtBoundary(
                id,
                "task_token_ceiling",
                (rootId, wrapper) -> delegation.stopAtTokenCeiling(rootId, wrapper));
    }

    /**
     * Reconcile saved work at a stop boundary without starting providers, child conversations, or tool
     * handlers; accepted calls that were never dispatched settle with {@code undispatchedOutcome}.
     */
    private void settleAtBoundary(
            String id,
            String undispatchedOutcome,
            BiFunction<String, AcceptedCall, Object> stopDelegation) {
        for (var call : repository.get(id).calls) {
            if (call.state == CallState.dispatched && settlement.delegationRecoveryCall(id, call)
                    && repository.receipt(id, call.id).isEmpty()) {
                Object result = stopDelegation.apply(id, call);
                String raw = DelegationService.encode(result);
                String status = result instanceof Map<?, ?> envelope
                        ? Objects.toString(envelope.get("status"), "failed")
                        : "failed";
                boolean uncertain = repository.get(id).blockers.contains("destination_inspection_required");
                publishOutcome(
                        id,
                        call,
                        new Outcome(
                                !Set.of("complete", "partial", "needs_input").contains(status),
                                uncertain
                                        ? "uncertain"
                                        : "superseded".equals(status) ? "superseded" : "confirmed",
                                raw,
                                raw,
                                false,
                                uncertain));
            }
        }
        recoverSettlements(id);
        settleUndispatched(id, undispatchedOutcome);
    }

    /** Yield to conversation progression before dispatch when durable control input is pending. */
    private boolean inputPending(String id, CancellationHandle cancellation) {
        var snapshot = repository.get(id);
        return snapshot.cancellationRequested || cancellation.cancelled()
                || (childRepository != null
                        ? !childRepository.current()
                        : repository.inbox(id).stream()
                                .anyMatch(c -> !snapshot.consumedInboxIds.contains(c.id())));
    }

    private ExecutionSnapshot update(String id, Consumer<ExecutionSnapshot> update) {
        return repository.update(id, -1, update);
    }

    private static String progress(String text) {
        if (text == null) {
            return null;
        }
        return text.substring(
                0,
                text.offsetByCodePoints(0, Math.min(256, text.codePointCount(0, text.length()))));
    }

    AcceptedCall accept(ExecutionSnapshot snapshot, InferenceToolCalls.Call decoded) {
        return ToolCallContracts.accept(decoded, snapshot.scope, settlement.bindingId(decoded.id()));
    }

    @Override
    public void close() {
        operations.shutdownNow();
    }
}
