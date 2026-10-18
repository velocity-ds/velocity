package net.velocity.inference;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.velocity.inference.objects.InferenceCapability;
import net.velocity.inference.objects.InferenceRequest;
import net.velocity.inference.objects.InferenceResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InferenceProfileRegistryTest {

    /** A configured provider that is never asked to generate anything. */
    private static final class FakeProvider implements InferenceProvider {

        @Override
        public String providerId() {
            return "fake";
        }

        @Override
        public Set<InferenceCapability> capabilities() {
            return Set.of(InferenceCapability.TEXT, InferenceCapability.STREAMING);
        }

        @Override
        public InferenceResponse infer(InferenceRequest request) {
            throw new UnsupportedOperationException("no inference in unit tests");
        }

        @Override
        public InferenceStream stream(InferenceRequest request) {
            throw new UnsupportedOperationException("no inference in unit tests");
        }

        @Override
        public long outputTokenAllowance() {
            return 1_024;
        }
    }

    private final InferenceService service = new InferenceService(new FakeProvider());

    @Test
    void profilesBindAProviderModelAndCapacityWithAStableVersion() {
        var profile = profile("default", "model-x");
        assertThat(profile.providerId()).isEqualTo("fake");
        assertThat(profile.model()).isEqualTo("model-x");
        assertThat(profile.version()).matches("sha256:[0-9a-f]{64}");
        assertThat(profile.version()).isEqualTo(profile("default", "model-x").version());
        assertThat(profile.version()).isNotEqualTo(profile("default", "model-y").version());
        assertThat(profile.service()).isSameAs(service);
    }

    @Test
    void profilesMustMatchTheirServiceAndFitTheirCapacity() {
        assertThatThrownBy(() -> new InferenceProfile("p", "fake", "m", 8_192, 7_000, 1_024, 256, service))
                .hasMessageContaining("exceed context capacity");
        assertThatThrownBy(() -> new InferenceProfile("p", "other", "m", 8_192, 4_096, 1_024, 256, service))
                .hasMessageContaining("does not match its provider");
        assertThatThrownBy(() -> new InferenceProfile("p", "fake", "m", 8_192, 4_096, 2_048, 256, service))
                .hasMessageContaining("does not match its provider");
        assertThatThrownBy(() -> new InferenceProfile("p", "fake", "m", 8_192, 0, 1_024, 256, service))
                .hasMessageContaining("must be positive");
        assertThatThrownBy(() -> new InferenceProfile(" ", "fake", "m", 8_192, 4_096, 1_024, 256, service))
                .hasMessage("Inference profile id is required");
        var unavailable = new InferenceService((InferenceProvider) null);
        assertThatThrownBy(
                () -> new InferenceProfile("p", "fake", "m", 8_192, 4_096, 1_024, 256, unavailable))
                .hasMessageContaining("does not match its provider");
        // Zero capacity means undeclared, so any allowances fit.
        assertThat(
                new InferenceProfile("p", "fake", "m", 0, 1_000_000, 1_024, 256, service).contextCapacity())
                .isZero();
    }

    @Test
    void registryResolvesOnlyConfiguredProfilesAndNeverFallsBack() {
        var registry = new InferenceProfileRegistry(
                List.of(
                        profile(InferenceProfileRegistry.DEFAULT, "local"),
                        profile(InferenceProfileRegistry.CLOUD_FALLBACK, "cloud")));
        assertThat(registry.available("default")).isTrue();
        assertThat(registry.available("missing")).isFalse();
        assertThat(registry.find(null)).isEmpty();
        assertThat(registry.require("default").model()).isEqualTo("local");
        assertThat(registry.cloudFallback()).map(InferenceProfile::model).contains("cloud");
        assertThatThrownBy(() -> registry.require("missing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Inference profile 'missing' is unavailable");
        assertThat(new InferenceProfileRegistry(List.of()).cloudFallback()).isEmpty();
    }

    @Test
    void duplicateProfileIdsFailStartup() {
        assertThatThrownBy(
                () -> new InferenceProfileRegistry(List.of(profile("default", "a"), profile("default", "b"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Duplicate inference profile: default");
        assertThatThrownBy(() -> new InferenceProfileRegistry(null)).isInstanceOf(NullPointerException.class);
    }

    private InferenceProfile profile(String id, String model) {
        return new InferenceProfile(id, "fake", model, 8_192, 4_096, 1_024, 256, service);
    }
}
