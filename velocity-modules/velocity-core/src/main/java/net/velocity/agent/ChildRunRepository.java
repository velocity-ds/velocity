package net.velocity.agent;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import net.velocity.agent.persistence.ExecutionRepository;
import net.velocity.agent.state.*;
import net.velocity.inference.objects.InferenceResponse;

/**
 * A conversation-state view, never a separately admitted execution. Every mutation is
 * committed to the root's optimistic document; resources and cancellation stay root-owned.
 */
final class ChildRunRepository implements ExecutionRepository {

    private final ExecutionRepository roots;
    private final String rootId;
    final String childId;
    private final long deadline;

    ChildRunRepository(ExecutionRepository roots, String rootId, String childId, long deadline) {
        this.roots = roots;
        this.rootId = rootId;
        this.childId = childId;
        this.deadline = deadline;
    }

    ExecutionSnapshot root() {
        return roots.get(rootId);
    }

    ChildRun child() {
        return root().childRuns.get(childId);
    }

    boolean current() {
        var root = root();
        return current(root, root.childRuns.get(childId));
    }

    private boolean current(ExecutionSnapshot root, ChildRun child) {
        return root.status == ExecutionStatus.running && !root.cancellationRequested
                && root.goalGeneration == child.goalGeneration
                && roots.inbox(rootId).stream().noneMatch(c -> !root.consumedInboxIds.contains(c.id()));
    }

    private void requireCapacity(ExecutionSnapshot root, ChildRun child) {
        if (!current(root, child)) {
            throw new CapacityException("superseded_or_cancelled");
        }
        if (child.status != ExecutionStatus.running || root.calls.stream().noneMatch(
                call -> call.id.equals(child.parentCallId)
                        && call.state == CallState.dispatched
                        && Objects.equals(call.binding, child.parentCallBinding))) {
            throw new CapacityException("child_already_settled");
        }
        if (System.nanoTime() >= deadline) {
            throw new CapacityException("delegation_timeout");
        }
    }

    void admitProviderRequest(String source, long estimatedTokens) {
        roots.update(rootId, -1, root -> {
            var child = root.childRuns.get(childId);
            requireCapacity(root, child);
            if (source == null || source.isBlank() || source.length() > 512
                    || child.usageSources.containsKey(source)
                    || child.requestReservations.containsKey(source)) {
                throw new CapacityException("provider_request_already_admitted");
            }
            long reservation = Math.max(2048, estimatedTokens);
            child.providerRequests++;
            root.delegatedModelRequests++;
            child.requestReservations.put(source, reservation);
            child.reservedTokens += reservation;
            root.delegatedReservedTokens += reservation;
            UsageAccounting.observe(child, source, null, true);
            UsageAccounting.observe(root, usageSource(source), null, true);
            if (source.startsWith("model:")) {
                child.modelRequestInFlight = true;
                child.activeUsageSource = source;
            }
            child.updatedAt = Instant.now();
            progress(root, child);
        });
    }

    void observe(String source, InferenceResponse.Usage usage, boolean missing) {
        // Late observations are permitted after cancellation, but the root tombstone still wins.
        roots.update(rootId, -1, root -> {
            var child = root.childRuns.get(childId);
            UsageAccounting.observe(child, source, usage, missing);
            UsageAccounting.observe(root, usageSource(source), usage, missing);
            if (usage != null && !missing && !source.startsWith("model:")) {
                releaseReservation(root, child, source);
            }
            child.updatedAt = Instant.now();
        });
    }

    private String usageSource(String source) {
        return "delegated:" + childId + ":" + source;
    }

    void modelIntent(String source, long estimate) {
        admitProviderRequest(source, estimate);
    }

    void responseSaved() {
        roots.update(rootId, -1, root -> {
            var child = root.childRuns.get(childId);
            releaseKnownModelReservation(root, child);
            child.modelRequestInFlight = false;
            child.activeUsageSource = null;
        });
    }

    long remainingMillis() {
        return Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
    }

    @Override
    public List<String> childProfileToolNames(String profile, int version) {
        return roots.childProfileToolNames(profile, version);
    }

    @Override
    public ExecutionSnapshot get(String id) {
        checkId(id);
        return view(root());
    }

    private ExecutionSnapshot view(ExecutionSnapshot root) {
        var child = root.childRuns.get(childId);
        var view = new ExecutionSnapshot();
        child.copyTo(view);
        view.executionId = rootId;
        view.formatVersion = root.formatVersion;
        view.purpose = root.purpose;
        // A child is pinned to its own binding and never sees the root's fallback, transition or compact.
        view.inference = InferenceState.pinned(child.inferenceBinding);
        view.conversationId = childId;
        view.originalRequest = child.objective;
        view.currentGoal = child.objective;
        view.scope = root.scope;
        view.goalGeneration = child.goalGeneration;
        view.createdAt = child.createdAt;
        view.updatedAt = child.updatedAt;
        view.revision = root.revision;
        view.automaticResumption = root.automaticResumption;
        view.cancellationRequested = root.cancellationRequested;
        return view;
    }

    @Override
    public ExecutionSnapshot update(String id, long revision, Consumer<ExecutionSnapshot> mutation) {
        checkId(id);
        var saved = roots.update(rootId, revision, root -> {
            var child = root.childRuns.get(childId);
            int beforeTools = child.toolCalls;
            int beforeMessages = child.messages.size();
            var view = view(root);
            mutation.accept(view);
            int dispatches = view.toolCalls - beforeTools;
            if (dispatches > 0) {
                requireCapacity(root, child);
                root.delegatedToolCalls += dispatches;
            }
            view.copyTo(child);
            if (child.messages.size() > beforeMessages && child.messages.getLast()
                    .role() == net.velocity.inference.objects.InferenceMessage.Role.ASSISTANT) {
                int headroom = Math.min(16_384, child.maxStateBytes / 4);
                if (net.velocity.agent.persistence.ExecutionStateCodec
                        .encodedBytes(child) > child.maxStateBytes - headroom) {
                    throw new CapacityException("child_state_limit");
                }
                releaseKnownModelReservation(root, child);
                child.modelRequestInFlight = false;
                child.activeUsageSource = null;
            }
            child.updatedAt = Instant.now();
            progress(root, child);
        });
        return view(saved);
    }

    @Override
    public List<Correction> inbox(String id) {
        checkId(id);
        return List.of();
    }

    @Override
    public Optional<Settlement> receipt(String id, String callId) {
        checkId(id);
        return roots.receipt(rootId, childId, callId);
    }

    @Override
    public void receipt(String id, Settlement receipt) {
        checkId(id);
        roots.receipt(rootId, childId, receipt);
    }

    @Override
    public String artifact(String id, String contents) {
        checkId(id);
        return roots.artifact(rootId, contents);
    }

    @Override
    public String artifact(String id, String name, long offset, int limit) {
        checkId(id);
        return roots.artifact(rootId, name, offset, limit);
    }

    @Override
    public void diagnostic(String id, String code, String raw) {
        checkId(id);
        roots.diagnostic(rootId, code, raw);
    }

    private void checkId(String id) {
        if (!rootId.equals(id)) {
            throw new SecurityException("Child root mismatch");
        }
    }

    private static void progress(ExecutionSnapshot root, ChildRun child) {
        root.calls.stream()
                .filter(call -> call.id.equals(child.parentCallId) && call.state == CallState.dispatched)
                .findFirst().ifPresent(
                        call -> call.progress = "Child " + child.profile + ": "
                                + child.providerRequests + " provider requests, " + child.toolCalls
                                + " tools dispatched");
    }

    private static void releaseKnownModelReservation(ExecutionSnapshot root, ChildRun child) {
        var usage = child.usageSources.get(child.activeUsageSource);
        if (usage != null && !usage.missing) {
            releaseReservation(root, child, child.activeUsageSource);
        }
    }

    private static void releaseReservation(ExecutionSnapshot root, ChildRun child, String source) {
        Long reservation = child.requestReservations.remove(source);
        if (reservation != null) {
            child.reservedTokens -= reservation;
            root.delegatedReservedTokens -= reservation;
        }
    }

    @Override
    public ExecutionSnapshot create(ExecutionSnapshot snapshot) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<ExecutionSnapshot> list() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Correction inbox(String id, String text) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Correction inbox(String id, String text, SourceTurn turn, List<SourceMessage> messages) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void delete(String id, long revision) {
        throw new UnsupportedOperationException();
    }

    static final class CapacityException extends RuntimeException {

        CapacityException(String code) {
            super(code);
        }
    }
}
