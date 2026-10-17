package net.velocity.agent;

import net.velocity.agent.state.InferenceBinding;
import net.velocity.inference.InferenceProfile;
import net.velocity.inference.InferenceProfileRegistry;

/** Resolves saved identities without admitting a replacement profile or a new fallback. */
public final class PinnedInference {

    private PinnedInference() {
    }

    public enum Status {
        configured, unavailable, incompatible
    }

    public record Resolution(Status status, InferenceProfile profile) {

        public boolean available() {
            return status == Status.configured;
        }
    }

    public static Resolution resolve(InferenceBinding binding, InferenceProfileRegistry profiles) {
        if (binding == null) {
            return new Resolution(Status.unavailable, null);
        }
        var profile = profiles == null ? null : profiles.find(binding.profileId()).orElse(null);
        if (profile == null) {
            return new Resolution(Status.unavailable, null);
        }
        return binding.matches(profile)
                ? new Resolution(Status.configured, profile)
                : new Resolution(Status.incompatible, null);
    }
}
