package net.velocity.agent.state;

/**
 * Persisted task classification. Every admission uses {@link #generic}; the value is persisted so that a future
 * purpose can select its own budget regime without the run loop branching on it.
 */
public enum TaskPurpose {
    generic
}
