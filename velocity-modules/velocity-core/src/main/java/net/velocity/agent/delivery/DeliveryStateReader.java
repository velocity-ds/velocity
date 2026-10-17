package net.velocity.agent.delivery;

/** Read-only view of a delivery adapter's journal for administrative inspection. */
@FunctionalInterface
public interface DeliveryStateReader {

    /** Contains private upload URLs: callers must project only safe delivery fields. */
    DeliveryJournal.State deliveryState(String executionId);
}
