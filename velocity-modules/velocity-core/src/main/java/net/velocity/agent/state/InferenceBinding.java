package net.velocity.agent.state;

import net.velocity.inference.InferenceProfile;

/** Non-secret, immutable identity and admitted limits for one native conversation. */
public record InferenceBinding(
        String profileId,
        String profileVersion,
        String providerId,
        String model,
        long inputAllowance,
        long maxOutputTokens,
        long safetyMargin) {

    public InferenceBinding {
        if (profileId == null || profileId.isBlank() || profileVersion == null || profileVersion.isBlank()
                || providerId == null || providerId.isBlank() || model == null || model.isBlank()
                || inputAllowance <= 0 || maxOutputTokens <= 0 || safetyMargin < 0) {
            throw new IllegalArgumentException("Invalid inference binding");
        }
    }

    public static InferenceBinding of(InferenceProfile profile) {
        return new InferenceBinding(
                profile.id(),
                profile.version(),
                profile.providerId(),
                profile.model(),
                profile.inputAllowance(),
                profile.maxOutputTokens(),
                profile.safetyMargin());
    }

    public boolean matches(InferenceProfile profile) {
        return equals(of(profile));
    }
}
