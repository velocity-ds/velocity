package net.velocity.agent.scheduling;

import net.velocity.agent.state.ExecutionSnapshot;
import net.velocity.agent.state.TrustedScope;

/** Transport-independent scheduled delivery. An uncertain result must never be blindly resent. */
public interface ScheduleDelivery {

    enum State {
        succeeded, failed, uncertain
    }

    record DeliveryResult(State state, String receipt, String failure) {}

    /** Check the saved owner's present access when accepting or updating a destination. */
    void verify(TrustedScope owner, ScheduleDestination destination);

    default void validateText(ScheduleDestination destination, String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Scheduled message must not be blank");
        }
    }

    /** Rechecks access and uses the occurrence ID as the durable delivery identity. */
    DeliveryResult deliver(
            String occurrenceId,
            TrustedScope owner,
            ScheduleDestination destination,
            String text);

    /** Delivers a settled successful execution with its recorded usage when supported by the transport. */
    default DeliveryResult deliverExecution(
            String occurrenceId,
            TrustedScope owner,
            ScheduleDestination destination,
            ExecutionSnapshot execution) {
        return deliver(occurrenceId, owner, destination, execution.finalAnswer);
    }

    /** Recover an existing intent or receipt without authorizing any new remote write. */
    default DeliveryResult reconcile(
            String occurrenceId,
            TrustedScope owner,
            ScheduleDestination destination,
            String text) {
        return new DeliveryResult(State.failed, null, "Occurrence no longer authorizes a new delivery");
    }

    static ScheduleDelivery unavailable() {
        return new ScheduleDelivery() {

            @Override
            public void verify(TrustedScope owner, ScheduleDestination destination) {
                throw new ScheduleService.UnavailableException("Scheduled channel delivery is unavailable");
            }

            @Override
            public DeliveryResult deliver(
                    String occurrenceId,
                    TrustedScope owner,
                    ScheduleDestination destination,
                    String text) {
                return new DeliveryResult(State.failed, null, "Scheduled channel delivery is unavailable");
            }
        };
    }
}
