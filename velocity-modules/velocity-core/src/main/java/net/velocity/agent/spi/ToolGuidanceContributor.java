package net.velocity.agent.spi;

import java.util.Objects;

import net.velocity.agent.ComposedContextHooks;
import net.velocity.agent.ToolRegistry;
import net.velocity.agent.state.TrustedScope;

/**
 * Adds registered-tool guidance as supplemental instructions.
 *
 * <p>A feature contributes its guidance as an ordinary {@link ContextContributor} bean built with
 * {@link #whenRegistered}, {@link #of} or {@link #allRegistered}:
 *
 * <ul>
 *   <li><b>Ordering.</b> Contributor beans are applied in Spring order ({@code @Order} on the bean method, lower
 *       first; unordered beans last, in bean-definition order); every contributor's text is appended after a
 *       newline by {@link ComposedContextHooks}. Give each bean an explicit {@code @Order}; the values in use are
 *       listed in {@code docs/agent-gateway.md}. The order is model-visible.
 *   <li><b>Registered-tool conditions.</b> The tool registry is complete before any contributor bean is created
 *       (the contributor depends on it), so presence is decided once at construction. A feature whose tools are
 *       not registered contributes nothing.
 *   <li><b>Validation.</b> A blank text or a blank tool name is rejected at construction, so the application
 *       context fails to start instead of silently dropping guidance.
 * </ul>
 */
public final class ToolGuidanceContributor implements ContextContributor {

    private final String guidance;

    private ToolGuidanceContributor(String guidance) {
        this.guidance = Objects.requireNonNull(guidance);
    }

    /** Guidance that applies only when every named tool is registered; otherwise an inert contributor. */
    public static ContextContributor whenRegistered(ToolRegistry tools, String text, String... toolNames) {
        requireText(text);
        if (toolNames.length == 0) {
            throw new IllegalArgumentException("Guidance requires at least one tool name");
        }
        return allRegistered(tools, toolNames) ? new ToolGuidanceContributor(text) : none();
    }

    /** Fixed guidance whose condition the caller already decided; a null text yields an inert contributor. */
    public static ContextContributor of(String text) {
        if (text == null) {
            return none();
        }
        requireText(text);
        return new ToolGuidanceContributor(text);
    }

    /** True when every named tool is registered. Blank names are rejected. */
    public static boolean allRegistered(ToolRegistry tools, String... toolNames) {
        Objects.requireNonNull(tools);
        boolean all = true;
        for (String name : toolNames) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Guidance tool names must not be blank");
            }
            all &= tools.find(name).isPresent();
        }
        return all;
    }

    private static ContextContributor none() {
        return new ContextContributor() {};
    }

    private static void requireText(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Guidance text must not be blank");
        }
    }

    @Override
    public String supplementalInstructions(TrustedScope scope) {
        return guidance;
    }
}
