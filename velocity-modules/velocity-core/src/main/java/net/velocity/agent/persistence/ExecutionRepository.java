package net.velocity.agent.persistence;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import net.velocity.agent.state.Correction;
import net.velocity.agent.state.ExecutionSnapshot;
import net.velocity.agent.state.Settlement;
import net.velocity.agent.state.SourceMessage;
import net.velocity.agent.state.SourceTurn;

public interface ExecutionRepository {

    ExecutionSnapshot create(ExecutionSnapshot snapshot);

    ExecutionSnapshot get(String executionId);

    List<ExecutionSnapshot> list();

    /** The live execution admitted to continue the given one, if any. */
    default Optional<String> continuationOf(String sourceExecutionId) {
        return list().stream().filter(snapshot -> sourceExecutionId.equals(snapshot.continuedFrom))
                .map(snapshot -> snapshot.executionId).findFirst();
    }

    ExecutionSnapshot update(String executionId, long expectedRevision, Consumer<ExecutionSnapshot> mutation);

    Correction inbox(String executionId, String text);

    Correction inbox(
            String executionId,
            String text,
            SourceTurn sourceTurn,
            List<SourceMessage> sourceMessages);

    List<Correction> inbox(String executionId);

    void receipt(String executionId, Settlement settlement);

    Optional<Settlement> receipt(String executionId, String callId);

    /** Child native call IDs remain local to the child; the enclosing run is trusted provenance. */
    default void receipt(String executionId, String childId, Settlement settlement) {
        ExecutionStateCodec.validateSettlement(settlement);
        if (childId == null || childId.isBlank()) {
            throw new IllegalArgumentException("Child ID is required");
        }
        update(executionId, -1, snapshot -> {
            var child = snapshot.childRuns.get(childId);
            if (child == null || !executionId.equals(child.rootExecutionId)) {
                throw new ConflictException("Receipt has no owned child run");
            }
            var call = child.calls.stream().filter(item -> item.id.equals(settlement.callId())).findFirst()
                    .orElseThrow(() -> new ConflictException("Child receipt has no accepted call"));
            if (!Objects.equals(call.binding, settlement.binding())) {
                throw new ConflictException("Child receipt does not match accepted call binding");
            }
            Settlement previous = child.receipts.putIfAbsent(settlement.callId(), settlement);
            if (previous != null && !previous.equals(settlement)) {
                throw new ConflictException("Child call already has a different receipt");
            }
        });
    }

    default Optional<Settlement> receipt(String executionId, String childId, String callId) {
        if (childId == null || childId.isBlank() || callId == null || callId.isBlank()) {
            throw new IllegalArgumentException("Child and call IDs are required");
        }
        var child = get(executionId).childRuns.get(childId);
        if (child == null || !executionId.equals(child.rootExecutionId)) {
            throw new ConflictException("Receipt has no owned child run");
        }
        return Optional.ofNullable(child.receipts.get(callId));
    }

    /**
     * The tool names a persisted child run may call, resolved from the delegation profile registry the
     * repository validates against.
     *
     * @throws IllegalArgumentException when the profile or version is unknown
     */
    List<String> childProfileToolNames(String profile, int version);

    String artifact(String executionId, String contents);

    String artifact(String executionId, String name, long offset, int limit);

    void diagnostic(String executionId, String code, String raw);

    void delete(String executionId, long expectedRevision);
}
