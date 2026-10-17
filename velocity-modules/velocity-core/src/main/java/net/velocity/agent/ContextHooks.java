package net.velocity.agent;

import java.util.List;

import net.velocity.agent.state.*;
import net.velocity.inference.objects.InferenceMessage;

/**
 * Domain retrieval returns context only. It cannot modify the trusted scope or
 * authorize calls.
 */
public interface ContextHooks extends AutoCloseable {

    default String instructions(TrustedScope scope) {
        return null;
    }

    /** Additional trusted guidance applied at inference time, including direct gateway admissions. */
    default String supplementalInstructions(TrustedScope scope) {
        return null;
    }

    default List<InferenceMessage> history(TrustedScope scope, SourceTurn turn) {
        return List.of();
    }

    default List<InferenceMessage> prepare(ExecutionSnapshot snapshot, List<InferenceMessage> messages) {
        return messages;
    }

    default void started(ExecutionSnapshot snapshot) {
    }

    default void completed(ExecutionSnapshot snapshot) {
    }

    default void deleted(String executionId) {
    }

    @Override
    default void close() {
    }
}
