package net.velocity.agent;

/** No admission or inbox change was committed; callers may retry the same trusted request. */
public final class AdmissionCapacityException extends IllegalStateException {

    public AdmissionCapacityException() {
        super("agent_queue_full: installation task queue is full; retry later");
    }
}
