package net.velocity.inference;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Resolves only configured profiles; an unavailable profile never falls back to another. */
public final class InferenceProfileRegistry {

    public static final String DEFAULT = "default";
    public static final String CLOUD_FALLBACK = "cloud-fallback";

    private final Map<String, InferenceProfile> profiles;

    public InferenceProfileRegistry(Collection<InferenceProfile> profiles) {
        Objects.requireNonNull(profiles, "profiles");
        Map<String, InferenceProfile> indexed = new LinkedHashMap<>();
        for (InferenceProfile profile : profiles) {
            Objects.requireNonNull(profile, "profile");
            if (indexed.putIfAbsent(profile.id(), profile) != null) {
                throw new IllegalArgumentException("Duplicate inference profile: " + profile.id());
            }
        }
        this.profiles = Map.copyOf(indexed);
    }

    public Optional<InferenceProfile> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(profiles.get(id));
    }

    /** Explicitly configured continuation candidate; callers must still honor the task's admitted policy. */
    public Optional<InferenceProfile> cloudFallback() {
        return find(CLOUD_FALLBACK);
    }

    public boolean available(String id) {
        return find(id).isPresent();
    }

    public InferenceProfile require(String id) {
        return find(id).orElseThrow(
                () -> new IllegalStateException("Inference profile '" + id + "' is unavailable"));
    }
}
