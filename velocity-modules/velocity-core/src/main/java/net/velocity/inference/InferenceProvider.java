package net.velocity.inference;

import java.util.EnumSet;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;

import net.velocity.inference.objects.InferenceCapability;
import net.velocity.inference.objects.InferenceRequest;
import net.velocity.inference.objects.InferenceResponse;

public interface InferenceProvider {

    String providerId();

    /** Non-secret model identity for accounting intent; custom providers may leave it unknown. */
    default String configuredModel() {
        return null;
    }

    Set<InferenceCapability> capabilities();

    InferenceResponse infer(InferenceRequest request);

    InferenceStream stream(InferenceRequest request);

    /** Provider tokenizer preflight without generation; custom providers may leave counting unavailable. */
    default OptionalLong countInputTokens(InferenceRequest request) {
        return OptionalLong.empty();
    }

    /** Read-only output reservation metadata; existing custom providers retain the conservative fallback. */
    default long outputTokenAllowance() {
        return 2048;
    }

    default String infer(String input) {
        return infer(InferenceRequest.text(input)).text();
    }

    default InferenceStream stream(String input) {
        return stream(InferenceRequest.text(input));
    }

    default void requireCapabilities(InferenceRequest request, boolean streaming) {
        Objects.requireNonNull(request, "request");
        var missing = EnumSet.copyOf(request.requiredCapabilities());
        if (streaming) {
            missing.add(InferenceCapability.STREAMING);
        }
        missing.removeAll(capabilities());
        if (!missing.isEmpty()) {
            throw new UnsupportedOperationException(
                    "Provider '" + providerId() + "' does not support " + missing);
        }
    }
}
