package net.velocity.agent;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.velocity.agent.persistence.ExecutionRepository;
import net.velocity.agent.state.*;
import net.velocity.agent.tools.DelegateWorkTool;
import net.velocity.inference.objects.InferenceContent;
import net.velocity.inference.objects.InferenceMessage;

/**
 * Durable receipts and native result settlement for accepted calls. The receipt is written before the result
 * is committed to the conversation, so a crash between the two is recovered by rereading the receipt, and
 * results are appended in accepted order even when a later sibling settles first. Also owns the call identity
 * rules (exact binding, delegation classification) that settlement and recovery share.
 */
final class CallSettlement {

    private static final Logger log = LoggerFactory.getLogger(CallSettlement.class);
    private final ExecutionRepository repository;
    private final ToolRegistry tools;
    private final ChildRunRepository childRepository;

    CallSettlement(ExecutionRepository repository, ToolRegistry tools) {
        this.repository = repository;
        this.tools = tools;
        this.childRepository = repository instanceof ChildRunRepository child ? child : null;
    }

    /** True while any accepted call waits for the requester's decision. */
    static boolean awaitingApproval(ExecutionSnapshot snapshot) {
        return snapshot.calls.stream().anyMatch(AcceptedCall::awaitingApproval);
    }

    static AcceptedCall call(ExecutionSnapshot snapshot, String id) {
        return snapshot.calls.stream().filter(c -> c.id.equals(id)).findFirst().orElseThrow();
    }

    static String preview(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    /** Receipts are durable before the corresponding native result is committed to the snapshot. */
    void settle(
            String id,
            AcceptedCall accepted,
            boolean error,
            String outcome,
            String text,
            String artifact) {
        var snapshot = repository.get(id);
        var existing = call(snapshot, accepted.id);
        if (existing.state == CallState.settled) {
            return;
        }
        var receipt = repository.receipt(id, accepted.id).orElseGet(() -> {
            var created = new Settlement(
                    accepted.id,
                    accepted.binding,
                    error,
                    outcome,
                    text,
                    artifact,
                    Instant.now());
            if (delegationRecoveryCall(id, accepted)) {
                // The repository lock also covers the durable inbox. Artifact storage may have
                // allowed a correction to arrive since the wrapper returned its candidate result.
                update(id, s -> {
                    Settlement current = created;
                    if (s.cancellationRequested || s.goalGeneration != accepted.goalGeneration
                            || repository.inbox(id).stream()
                                    .anyMatch(c -> !s.consumedInboxIds.contains(c.id()))) {
                        current = new Settlement(
                                accepted.id,
                                accepted.binding,
                                true,
                                "superseded",
                                DelegationService.encode(
                                        Map.of(
                                                "status",
                                                "superseded",
                                                "content_trust",
                                                "untrusted_data",
                                                "summary",
                                                "The child assignment is no longer current")),
                                artifact,
                                Instant.now());
                    }
                    repository.receipt(id, current);
                });
                return repository.receipt(id, accepted.id).orElseThrow();
            }
            repository.receipt(id, created);
            return created;
        });
        if (!Objects.equals(receipt.binding(), accepted.binding)) {
            throw new IllegalStateException("Settlement binding mismatch");
        }
        update(id, s -> {
            var call = call(s, accepted.id);
            if (call.state == CallState.settled) {
                return;
            }
            call.state = CallState.settled;
            call.settlement = receipt;
            if (childRepository == null && call.modelTurn > 0
                    && s.calls.stream().filter(sibling -> sibling.modelTurn == call.modelTurn)
                            .allMatch(sibling -> sibling.state == CallState.settled)) {
                s.completedToolBatches.add(call.modelTurn);
            }
            // Recover the question together with its receipt; a crash must not
            // lose the input wait and dispatch the remaining calls without an answer.
            if ("ask_user".equals(call.name) && !receipt.error()) {
                s.pendingQuestions
                        .add(new Question(UUID.randomUUID().toString(), (String) call.input.get("question")));
            }
            if ("uncertain".equals(receipt.outcome()) || "invalid_output".equals(receipt.outcome())
                    && tools.find(Objects.toString(call.name, "")).map(d -> !d.read()).orElse(true)) {
                if (!s.blockers.contains("destination_inspection_required")) {
                    s.blockers.add("destination_inspection_required");
                }
            }
            // Result order follows the accepted queue even if a later sibling fails validation first.
            Set<String> emitted = new HashSet<>();
            s.messages.stream().flatMap(m -> m.content().stream())
                    .filter(InferenceContent.ToolResult.class::isInstance)
                    .map(InferenceContent.ToolResult.class::cast).forEach(r -> emitted.add(r.toolUseId()));
            for (var ordered : s.calls) {
                if (ordered.state != CallState.settled) {
                    break;
                }
                if (emitted.add(ordered.id)) {
                    s.messages.add(
                            new InferenceMessage(
                                    InferenceMessage.Role.USER,
                                    List.of(
                                            ToolCallContracts.result(
                                                    ordered,
                                                    ordered.settlement.preview(),
                                                    ordered.settlement.error()))));
                }
            }
        });
        log.info(
                "executionId={} stage=settlement modelTurn={} outcome={}",
                id,
                snapshot.modelTurns,
                receipt.outcome());
    }

    /** Accepted calls that never dispatched settle with the given outcome and no external effect. */
    void settleUndispatched(String id, String outcome) {
        for (var call : repository.get(id).calls) {
            if (call.state == CallState.accepted) {
                settle(id, call, true, outcome, "Call not dispatched: " + outcome, null);
            }
        }
    }

    /** Dispatched calls whose handler never returned settle as interrupted; their external outcome is unknown. */
    void settleDispatched(String id, String outcome) {
        for (var call : repository.get(id).calls) {
            if (call.state == CallState.dispatched) {
                settle(id, call, true, outcome, "Dispatch interrupted; external outcome unknown", null);
            }
        }
    }

    /** True for the root's trusted delegation primitive; children never delegate. */
    boolean delegationCall(AcceptedCall call) {
        return childRepository == null && tools.find(Objects.toString(call.name, ""))
                .map(d -> d.handler() instanceof DelegateWorkTool.Handler).orElse(false);
    }

    /** A delegation call, or a saved {@code delegate_work} call that already has a child run to recover. */
    boolean delegationRecoveryCall(String id, AcceptedCall call) {
        return delegationCall(call) || childRepository == null && "delegate_work".equals(call.name)
                && repository.get(id).childRuns
                        .containsKey(ChildRun.id(id, call.id, call.binding, call.goalGeneration));
    }

    String binding(ExecutionSnapshot snapshot, AcceptedCall call) {
        return ToolCallContracts.binding(snapshot.scope, bindingId(call.id), call);
    }

    String bindingId(String callId) {
        return childRepository == null ? callId : childRepository.childId + ":" + callId;
    }

    private ExecutionSnapshot update(String id, Consumer<ExecutionSnapshot> update) {
        return repository.update(id, -1, update);
    }
}
