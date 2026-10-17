package net.velocity.agent;

import java.util.Objects;
import java.util.Set;

import net.velocity.agent.config.ApprovalProperties;

/**
 * Decides, from trusted registration metadata alone, whether an accepted call must wait for the requester's
 * approval before it is dispatched. Reads, questions and delegation never require approval; a listed tool
 * always does; otherwise the tool's category decides. A tool whose handler is a
 * {@link ToolRegistry.ConfirmingHandler} is held independently of these settings. Model arguments never
 * influence the decision.
 */
public final class ToolApprovalPolicy {

    private static final Set<String> NEVER = Set.of("agent.question", "agent.delegation");
    private final boolean enabled;
    private final Set<String> categories;
    private final Set<String> tools;
    private final Set<String> exempt;

    private ToolApprovalPolicy(
            boolean enabled,
            Set<String> categories,
            Set<String> tools,
            Set<String> exempt) {
        this.enabled = enabled;
        this.categories = Set.copyOf(categories);
        this.tools = Set.copyOf(tools);
        this.exempt = Set.copyOf(exempt);
    }

    public static ToolApprovalPolicy disabled() {
        return new ToolApprovalPolicy(false, Set.of(), Set.of(), Set.of());
    }

    public static ToolApprovalPolicy of(ApprovalProperties properties) {
        Objects.requireNonNull(properties);
        return new ToolApprovalPolicy(
                properties.enabled(),
                properties.categorySet(),
                properties.toolSet(),
                properties.exemptToolSet());
    }

    public boolean enabled() {
        return enabled;
    }

    /** The policy's own rules: enabled, not exempt, and listed by name or by category. */
    public boolean requiresApproval(ToolRegistry.Definition tool) {
        if (!enabled || tool == null || exempt.contains(tool.name())) {
            return false;
        }
        if (tools.contains(tool.name())) {
            return true;
        }
        if (tool.read() || NEVER.contains(tool.category())) {
            return false;
        }
        return categories.contains(tool.category());
    }

    /**
     * A tool registered with a {@link ToolRegistry.ConfirmingHandler} waits for the requester even while the
     * policy is disabled; only an explicit exemption switches that off.
     */
    public boolean requiresConfirmation(ToolRegistry.Definition tool) {
        return tool != null && tool.requiresConfirmation() && !exempt.contains(tool.name());
    }

    /** True when an attended execution must hold this call for either reason. */
    public boolean holds(ToolRegistry.Definition tool) {
        return requiresApproval(tool) || requiresConfirmation(tool);
    }

    /** True when any registered tool can hold a call, so new tasks need the approval guidance. */
    public boolean guidanceNeeded(ToolRegistry tools) {
        return enabled || tools.definitions().stream().anyMatch(this::requiresConfirmation);
    }
}
