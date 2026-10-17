package net.velocity.agent.state;

/** Succeeded means that the model completed its response, not independently verified correctness. */
public enum ExecutionStatus {

    running, waiting_for_user, partial, blocked, succeeded, failed, cancelled;

    public boolean terminal() {
        return this != running && this != waiting_for_user;
    }
}
