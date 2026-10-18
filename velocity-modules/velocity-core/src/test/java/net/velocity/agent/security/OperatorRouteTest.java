package net.velocity.agent.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperatorRouteTest {

    @Test
    void literalApiRootsAreAccepted() {
        assertThat(OperatorRoute.of("/api/operator").sensitive()).isFalse();
        assertThat(OperatorRoute.secret("/api/operator/secrets").sensitive()).isTrue();
        assertThat(OperatorRoute.of("/api/agent/executions").root()).isEqualTo("/api/agent/executions");
        assertThat(OperatorRoute.of("/api/my-feature_2").root()).isEqualTo("/api/my-feature_2");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "/api",
            "/api/",
            "/api/operator/",
            "/operator/x",
            "/api/op/*",
            "/api/{feature}",
            "/api/op;x=1",
            "/api/op%41",
            "/api/.hidden",
            "/api/-dash",
            "/api/a/..",
            "/api//double",
            "api/operator",
            "/API/operator"})
    void anythingButALiteralNormalizedRootIsRejected(String root) {
        assertThatThrownBy(() -> OperatorRoute.of(root))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Invalid operator route root");
    }
}
