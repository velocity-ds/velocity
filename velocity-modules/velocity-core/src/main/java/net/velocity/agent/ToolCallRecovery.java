package net.velocity.agent;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import com.fasterxml.jackson.databind.json.JsonMapper;

import net.velocity.agent.persistence.ExecutionRepository;
import net.velocity.agent.state.*;

/**
 * Recovery of calls a crash or stop interrupted. A settled receipt is reused exactly; a dispatched call with
 * no receipt is reconciled from its child run or the handler's own destination receipt, and otherwise settles
 * as uncertain, which blocks the task until an operator inspects the destination. Recovery never starts a
 * provider request, a child conversation or a tool handler.
 */
final class ToolCallRecovery {

    private static final JsonMapper JSON = new JsonMapper();
    private final ExecutionRepository repository;
    private final ChildRunRepository childRepository;
    private final ToolRegistry tools;
    private final DelegationService delegation;
    private final List<String> childToolNames;
    private final CallSettlement settlement;
    private final ExecutionProgress progress;
    private final Function<ExecutionSnapshot, TrustedScope> executionScope;

    /**
     * @param executionScope the executor's trusted scope check, which re-verifies saved schedule and monitoring
     *        associations at reconciliation time
     */
    ToolCallRecovery(
            ExecutionRepository repository,
            ToolRegistry tools,
            DelegationService delegation,
            List<String> childToolNames,
            CallSettlement settlement,
            ExecutionProgress progress,
            Function<ExecutionSnapshot, TrustedScope> executionScope) {
        this.repository = repository;
        this.childRepository = repository instanceof ChildRunRepository child ? child : null;
        this.tools = tools;
        this.delegation = delegation;
        this.childToolNames = childToolNames;
        this.settlement = settlement;
        this.progress = progress;
        this.executionScope = executionScope;
    }

    void recoverSettlements(String id) {
        boolean uncertain = repository.get(id).blockers.contains("destination_inspection_required");
        for (var call : repository.get(id).calls) {
            if (call.settlement != null && ("uncertain".equals(call.settlement.outcome())
                    || "invalid_output".equals(call.settlement.outcome()) && tools
                            .find(Objects.toString(call.name, "")).map(d -> !d.read()).orElse(true))) {
                uncertain = true;
            }
            if (call.state == CallState.settled) {
                continue;
            }
            var receipt = repository.receipt(id, call.id);
            if (receipt.isPresent()) {
                settlement.settle(
                        id,
                        call,
                        receipt.get().error(),
                        receipt.get().outcome(),
                        receipt.get().preview(),
                        receipt.get().artifact());
                if ("uncertain".equals(receipt.get().outcome()) || "invalid_output"
                        .equals(receipt.get().outcome())
                        && tools.find(Objects.toString(call.name, "")).map(d -> !d.read()).orElse(true)) {
                    uncertain = true;
                }
            } else if (call.state == CallState.dispatched && settlement.delegationRecoveryCall(id, call)) {
                var snapshot = repository.get(id);
                String childId = ChildRun.id(id, call.id, call.binding, call.goalGeneration);
                var child = snapshot.childRuns.get(childId);
                boolean current = !snapshot.cancellationRequested
                        && snapshot.goalGeneration == call.goalGeneration
                        && repository.inbox(id).stream()
                                .noneMatch(c -> !snapshot.consumedInboxIds.contains(c.id()));
                if (child != null && child.result != null && current) {
                    String resultStatus;
                    try {
                        resultStatus = JSON.readTree(child.result).path("status").asText("failed");
                    } catch (Exception invalid) {
                        resultStatus = "failed";
                    }
                    settlement.settle(
                            id,
                            call,
                            !Set.of("complete", "partial", "needs_input").contains(resultStatus),
                            child.blockers.contains("destination_inspection_required")
                                    ? "uncertain"
                                    : "superseded".equals(resultStatus) ? "superseded" : "confirmed",
                            child.result,
                            repository.artifact(id, child.result));
                    uncertain |= child.blockers.contains("destination_inspection_required");
                } else if (!current || !snapshot.automaticResumption || !settlement.delegationCall(call)) {
                    Object result = delegation
                            .execute(id, call, new CancellationHandle(), System.nanoTime(), false);
                    String raw = DelegationService.encode(result);
                    settlement.settle(
                            id,
                            call,
                            true,
                            current ? "automatic_resumption_disabled" : "superseded",
                            raw,
                            repository.artifact(id, raw));
                    uncertain |= repository.get(id).blockers.contains("destination_inspection_required");
                }
                // Otherwise the root worker resumes this exact wrapper, without another dispatch charge.
            } else if (call.state == CallState.dispatched && reconcileAtomicMutation(id, call)) {
                /* destination receipt settled */ } else if (call.state == CallState.dispatched) {
                settlement.settle(
                        id,
                        call,
                        true,
                        "uncertain",
                        "Interrupted dispatch; destination inspection required",
                        null);
                uncertain = true;
            }
        }
        if (uncertain) {
            settlement.settleUndispatched(id, "destination_inspection_required");
            progress.stop(id, ExecutionStatus.blocked, "destination_inspection_required");
        }
    }

    private boolean reconcileAtomicMutation(String id, AcceptedCall call) {
        var snapshot = repository.get(id);
        var definition = tools.find(Objects.toString(call.name, "")).orElse(null);
        if (definition == null || !(definition.handler() instanceof ToolRegistry.ReconcilingHandler handler)
                || !Objects.equals(call.binding, settlement.binding(snapshot, call))
                || childRepository != null && !childToolNames.contains(call.name)
                || snapshot.admissionContext != null && snapshot.admissionContext.tools().stream()
                        .noneMatch(
                                pinned -> pinned.name().equals(call.name) && pinned.compatible(definition))) {
            return false;
        }
        try {
            executionScope.apply(snapshot);
            // This registered seam may only inspect the exact durable receipt. It cannot execute the handler.
            var result = handler.reconcile(
                    ToolCallExecutor.toolContext(
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
            if (result.isEmpty()) {
                return false;
            }
            JsonSchema.validate(definition.outputSchema(), result.get());
            String raw = JSON.writeValueAsString(result.get());
            boolean failed = definition.handler() instanceof ToolRegistry.FailureAwareHandler aware
                    && aware.failed(result.get());
            settlement.settle(
                    id,
                    call,
                    failed,
                    failed ? "tool_failed" : "confirmed",
                    CallSettlement.preview(raw, snapshot.limits.outputPreviewChars()),
                    repository.artifact(id, raw));
            return true;
        } catch (Exception unavailable) {
            return false;
        }
    }
}
