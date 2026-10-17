package net.velocity.agent.delegation;

import java.util.List;
import java.util.Map;

import net.velocity.agent.ToolRegistry;

/**
 * Validates the references a parent hands to a delegated child, and the citations the child reports back,
 * against the root task's immutable resources. The platform research service implements it; without one,
 * {@link #none()} refuses every reference so a child can never cite material the kernel cannot verify.
 */
@FunctionalInterface
public interface ChildReferenceValidator {

    /** Throws {@link IllegalArgumentException} for any reference that is not current and owned by the task. */
    void validateChildReferences(ToolRegistry.Context context, List<Map<String, Object>> references);

    static ChildReferenceValidator none() {
        return (context, references) -> {
            if (references != null && !references.isEmpty()) {
                throw new IllegalArgumentException("Delegated references are not available");
            }
        };
    }
}
