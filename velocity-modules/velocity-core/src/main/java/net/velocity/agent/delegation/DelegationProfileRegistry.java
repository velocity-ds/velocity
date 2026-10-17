package net.velocity.agent.delegation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import net.velocity.agent.ToolRegistry;
import net.velocity.agent.spi.DelegationProfile;
import net.velocity.inference.SystemPrompts;

/**
 * The immutable set of {@link DelegationProfile}s contributed by features, plus the model-visible text assembled
 * from them.
 *
 * <p>Contract:
 *
 * <ul>
 *   <li><b>Ordering.</b> Profiles come from {@link DelegationProfile} beans in Spring order ({@code @Order} on the
 *       bean method, lower first; unordered beans last). The order is model-visible: it fixes the {@code profile}
 *       enum of {@code delegate_work} and the order of the profile lines in its description and in the parent
 *       delegation guidance. Give every profile bean an explicit {@code @Order}; the values in use are listed in
 *       {@code docs/agent-gateway.md}.
 *   <li><b>Uniqueness and validation.</b> Profile names are unique; a duplicate or an invalid profile fails
 *       construction, so the application context fails to start. Only version {@value #VERSION} exists.
 *   <li><b>Unknown profiles.</b> Lookups for an unknown profile or version throw
 *       {@link IllegalArgumentException}; persisted child runs that name an unknown profile are rejected as corrupt
 *       state, exactly as before.
 *   <li><b>No profiles.</b> With no feature contributing a profile {@link #isEmpty()} is true and the
 *       {@code delegate_work} tool is not registered.
 * </ul>
 */
public final class DelegationProfileRegistry {

    public static final int VERSION = 1;

    private final Map<String, DelegationProfile> profiles = new LinkedHashMap<>();

    public DelegationProfileRegistry(List<DelegationProfile> contributed) {
        for (DelegationProfile profile : Objects.requireNonNull(contributed)) {
            if (profiles.putIfAbsent(profile.name(), profile) != null) {
                throw new IllegalArgumentException("Duplicate delegation profile: " + profile.name());
            }
        }
    }

    public boolean isEmpty() {
        return profiles.isEmpty();
    }

    /** Profile names in registration order. */
    public List<String> names() {
        return List.copyOf(profiles.keySet());
    }

    public boolean known(String profile, int version) {
        return version == VERSION && profile != null && profiles.containsKey(profile);
    }

    public List<String> toolNames(String profile, int version) {
        if (!known(profile, version)) {
            throw new IllegalArgumentException("Unknown delegation profile or version");
        }
        return profiles.get(profile).toolNames();
    }

    public List<ToolRegistry.Definition> resolve(String profile, int version, ToolRegistry registry) {
        List<String> required = toolNames(profile, version);
        List<String> unavailable = required.stream().filter(
                name -> registry.find(name)
                        .filter(ToolRegistry.Definition::trusted).isEmpty())
                .toList();
        if (!unavailable.isEmpty()) {
            throw new UnavailableProfileException(unavailable);
        }
        return required.stream().map(name -> registry.find(name).orElseThrow()).toList();
    }

    public String instructions(String profile) {
        toolNames(profile, VERSION);
        return SystemPrompts.CHILD_RUN_INSTRUCTIONS + "\n" + profiles.get(profile).childInstructions();
    }

    /** The {@code delegate_work} description: shared head, each profile's line(s) in order, shared tail. */
    public String toolDescription() {
        StringBuilder text = new StringBuilder(SystemPrompts.DELEGATE_WORK_DESCRIPTION_HEAD);
        profiles.values().forEach(profile -> {
            if (profile.parentDescription() != null) {
                text.append(profile.parentDescription());
            }
        });
        return text.append(SystemPrompts.DELEGATE_WORK_DESCRIPTION_TAIL).toString();
    }

    /** The parent's delegation guidance: each profile's guidance in order, then the shared guidance. */
    public String parentGuidance() {
        StringBuilder text = new StringBuilder();
        profiles.values().forEach(profile -> {
            if (profile.parentGuidance() != null) {
                text.append(profile.parentGuidance());
            }
        });
        return text.append(SystemPrompts.DELEGATION_INSTRUCTIONS_TAIL).toString();
    }

    public static final class UnavailableProfileException extends IllegalStateException {

        public UnavailableProfileException(List<String> missingTools) {
            super("Delegation profile unavailable: " + String.join(", ", missingTools));
        }
    }
}
