package net.velocity.agent;

import java.util.Objects;
import java.util.concurrent.CancellationException;

import net.velocity.agent.persistence.ExecutionRepository;
import net.velocity.agent.state.AcceptedCall;
import net.velocity.agent.state.CallState;
import net.velocity.agent.state.ExecutionSnapshot;
import net.velocity.agent.state.ExecutionStatus;

/** Resolves trusted run identity separately from root resource ownership. */
public final class CallProvenance {

    private CallProvenance() {
    }

    public record Resolved(ExecutionSnapshot execution, AcceptedCall call, String childRunId) {}

    public static Resolved current(ExecutionRepository repository, ToolRegistry.Context context) {
        context.cancellation().check();
        ExecutionSnapshot root = repository.get(context.executionId());
        if (!root.scope.equals(context.scope())) {
            throw rejected("not_found", "Owned execution not found");
        }
        if (root.cancellationRequested) {
            throw new CancellationException();
        }
        if (root.status != ExecutionStatus.running) {
            throw rejected("stale_goal", "Task is no longer running");
        }
        AcceptedCall call;
        if (context.childRunId() == null) {
            // Preserve format-1 root binding semantics and native call IDs.
            call = root.calls.stream().filter(
                    item -> Objects.equals(item.id, context.acceptedCallId())
                            && Objects.equals(item.binding, context.acceptedCallBinding()))
                    .findFirst()
                    .orElseThrow(() -> rejected("invalid_request", "Accepted call provenance is required"));
        } else {
            var child = root.childRuns.get(context.childRunId());
            if (child == null || !Objects.equals(child.id, context.childRunId())
                    || !Objects.equals(child.rootExecutionId, root.executionId) || root.monitoring != null
                    || !Objects.equals(
                            child.id,
                            net.velocity.agent.state.ChildRun.id(
                                    root.executionId,
                                    child.parentCallId,
                                    child.parentCallBinding,
                                    child.goalGeneration))) {
                throw rejected("invalid_request", "Child call provenance is required");
            }
            var wrapper = root.calls.stream().filter(
                    item -> Objects.equals(item.id, child.parentCallId)
                            && Objects.equals(item.binding, child.parentCallBinding))
                    .findFirst()
                    .orElseThrow(
                            () -> rejected(
                                    "invalid_request",
                                    "Delegation wrapper provenance does not match"));
            if (!"delegate_work".equals(wrapper.name) || wrapper.state != CallState.dispatched
                    || !Objects.equals(child.profile, wrapper.input.get("profile"))
                    || !Objects.equals(
                            wrapper.binding,
                            AuthorizationPolicy
                                    .binding(root.scope, wrapper.id, wrapper.name, wrapper.input))) {
                throw rejected("invalid_request", "Delegation wrapper is not an exact dispatched call");
            }
            if (child.status != ExecutionStatus.running || child.goalGeneration != root.goalGeneration
                    || wrapper.goalGeneration != child.goalGeneration) {
                throw rejected("stale_goal", "A task correction superseded this child call");
            }
            call = child.calls.stream().filter(
                    item -> Objects.equals(item.id, context.acceptedCallId())
                            && Objects.equals(item.binding, context.acceptedCallBinding()))
                    .findFirst()
                    .orElseThrow(
                            () -> rejected(
                                    "invalid_request",
                                    "Nested accepted call provenance does not match"));
            if (call.state != CallState.dispatched || !Objects.equals(
                    call.binding,
                    AuthorizationPolicy
                            .binding(root.scope, child.id + ":" + call.id, call.name, call.input))) {
                throw rejected("invalid_request", "Nested call is not an exact dispatched call");
            }
            try {
                if (!repository.childProfileToolNames(child.profile, child.profileVersion)
                        .contains(call.name)) {
                    throw rejected(
                            "invalid_request",
                            "Tool is outside the child's trusted capability profile");
                }
            } catch (IllegalArgumentException unavailable) {
                throw rejected("invalid_request", "Child capability profile is unavailable");
            }
        }
        if (call.goalGeneration != root.goalGeneration || repository.inbox(root.executionId).stream()
                .anyMatch(correction -> !root.consumedInboxIds.contains(correction.id()))) {
            throw rejected("stale_goal", "A task correction superseded this research call");
        }
        return new Resolved(root, call, context.childRunId());
    }

    private static Rejected rejected(String status, String message) {
        return new Rejected(status, message);
    }

    public static final class Rejected extends RuntimeException {

        private final String status;

        private Rejected(String status, String message) {
            super(message);
            this.status = status;
        }

        public String status() {
            return status;
        }
    }
}
