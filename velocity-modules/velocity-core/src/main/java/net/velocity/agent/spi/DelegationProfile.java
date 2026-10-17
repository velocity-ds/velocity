package net.velocity.agent.spi;

import java.util.List;
import java.util.regex.Pattern;

import net.velocity.agent.delegation.DelegationProfileRegistry;

/**
 * An immutable capability profile for an internal child run, contributed by a feature as an ordinary bean and
 * collected by the {@link DelegationProfileRegistry}. Registration, schemas and normal authorization still apply
 * to every tool the profile names; a profile grants no authority.
 *
 * @param name stable persisted identifier, {@code [a-z][a-z0-9_]{0,63}}
 * @param toolNames tools the child may call, in order; each must be a registered, trusted tool when the profile is
 *        resolved
 * @param childInstructions profile-specific child instructions appended after the shared child instructions
 * @param parentDescription optional line(s) of the {@code delegate_work} tool description that describe this
 *        profile, including the line break; {@code null} for none
 * @param parentGuidance optional line(s) of the parent's delegation guidance that describe this profile,
 *        including the line break; {@code null} for none
 */
public record DelegationProfile(
        String name,
        List<String> toolNames,
        String childInstructions,
        String parentDescription,
        String parentGuidance) {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final Pattern TOOL = Pattern.compile("[a-zA-Z0-9_-]{1,64}");

    public DelegationProfile {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid delegation profile name");
        }
        if (toolNames == null || toolNames.isEmpty()
                || toolNames.stream().anyMatch(tool -> tool == null || !TOOL.matcher(tool).matches())
                || toolNames.stream().distinct().count() != toolNames.size()) {
            throw new IllegalArgumentException(
                    "Delegation profile requires distinct valid tool names: " + name);
        }
        toolNames = List.copyOf(toolNames);
        if (childInstructions == null || childInstructions.isBlank()) {
            throw new IllegalArgumentException("Delegation profile requires child instructions: " + name);
        }
        if (parentDescription != null && parentDescription.isBlank()
                || parentGuidance != null && parentGuidance.isBlank()) {
            throw new IllegalArgumentException("Delegation profile fragments must not be blank: " + name);
        }
    }
}
