package net.velocity.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.velocity.agent.state.TrustedScope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CallBindingsTest {

    private static final String OWNER = "7b6a4a2e-1b2c-4d3e-8f90-0123456789ab";
    private static final TrustedScope SCOPE = new TrustedScope("W1", "U1", "C1", "B1", "slack", OWNER);

    @Test
    void bindingIsASha256HexDigest() {
        assertThat(CallBindings.binding(SCOPE, "call-1", "echo", Map.of("text", "hi")))
                .matches("[0-9a-f]{64}");
    }

    @Test
    void inputKeyOrderDoesNotChangeTheBinding() {
        var ordered = new LinkedHashMap<String, Object>();
        ordered.put("b", 2);
        ordered.put("a", 1);
        var reversed = new LinkedHashMap<String, Object>();
        reversed.put("a", 1);
        reversed.put("b", 2);
        assertThat(CallBindings.binding(SCOPE, "c", "t", ordered))
                .isEqualTo(CallBindings.binding(SCOPE, "c", "t", reversed));
    }

    @Test
    void numericRepresentationsAreNormalized() {
        assertThat(CallBindings.binding(SCOPE, "c", "t", Map.of("n", 10)))
                .isEqualTo(CallBindings.binding(SCOPE, "c", "t", Map.of("n", 10.0)));
        assertThat(CallBindings.binding(SCOPE, "c", "t", Map.of("n", List.of(Map.of("x", 1L)))))
                .isEqualTo(CallBindings.binding(SCOPE, "c", "t", Map.of("n", List.of(Map.of("x", 1.0d)))));
    }

    @Test
    void everyIdentityComponentChangesTheBinding() {
        String base = CallBindings.binding(SCOPE, "c", "t", Map.of());
        assertThat(CallBindings.binding(SCOPE, "other", "t", Map.of())).isNotEqualTo(base);
        assertThat(CallBindings.binding(SCOPE, "c", "u", Map.of())).isNotEqualTo(base);
        assertThat(CallBindings.binding(SCOPE, "c", "t", Map.of("k", "v"))).isNotEqualTo(base);
        assertThat(CallBindings.binding(SCOPE.withOrigin("http"), "c", "t", Map.of())).isNotEqualTo(base);
        var otherOwner = new TrustedScope(
                "W1",
                "U1",
                "C1",
                "B1",
                "slack",
                "0f1e2d3c-4b5a-4978-8695-a4b3c2d1e0f9");
        assertThat(CallBindings.binding(otherOwner, "c", "t", Map.of())).isNotEqualTo(base);
    }

    @Test
    void unownedScopeCannotBind() {
        var unowned = new TrustedScope("W1", "U1", "C1", "B1", "slack");
        assertThatThrownBy(() -> CallBindings.binding(unowned, "c", "t", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Cannot normalize tool binding")
                .hasCauseInstanceOf(SecurityException.class);
    }
}
