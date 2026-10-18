package net.velocity.agent.security;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.velocity.agent.spi.OperatorRouteContributor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperatorRoutesTest {

    @Test
    void coreRootsAreAlwaysProtected() {
        var routes = new OperatorRoutes(List.of());
        assertThat(routes.roots()).containsExactly(
                "/api/agent/executions",
                "/api/agent/knowledge",
                "/api/operator",
                "/api/operator/secrets");
        assertThat(routes.sensitiveRoots()).containsExactly("/api/operator/secrets");
    }

    @Test
    void servletPathsAreProtectedAtTheRootAndBeneathItOnly() {
        var routes = new OperatorRoutes(List.of());
        assertThat(routes.protectedServletPath("/api/operator")).isTrue();
        assertThat(routes.protectedServletPath("/api/operator/status")).isTrue();
        assertThat(routes.protectedServletPath("/api/agent/executions/abc")).isTrue();
        assertThat(routes.protectedServletPath("/api/operators")).isFalse();
        assertThat(routes.protectedServletPath("/api/agent")).isFalse();
        assertThat(routes.protectedServletPath("/velocity/hello")).isFalse();
    }

    @Test
    void browserPathsAreRecognizedBehindAnyContextPrefix() {
        var routes = new OperatorRoutes(List.of());
        assertThat(routes.protectedBrowserPath("/api/operator")).isTrue();
        assertThat(routes.protectedBrowserPath("/velocity/api/operator/status")).isTrue();
        assertThat(routes.protectedBrowserPath("/x/y/api/agent/executions")).isTrue();
        assertThat(routes.protectedBrowserPath("/api/operatorx/status")).isFalse();
        assertThat(routes.protectedBrowserPath("/public/api")).isFalse();
    }

    @Test
    void sensitivityFollowsTheSecretRoots() {
        var routes = new OperatorRoutes(List.of());
        assertThat(routes.sensitive("/api/operator/secrets")).isTrue();
        assertThat(routes.sensitive("/api/operator/secrets/abc")).isTrue();
        assertThat(routes.sensitive("/api/operator/status")).isFalse();
        assertThat(routes.sensitive("/api/operator/secretsx")).isFalse();
    }

    @Test
    void contributionsAddRootsAndCanOnlyRaiseSensitivity() {
        OperatorRouteContributor feature = () -> List.of(
                OperatorRoute.of("/api/mcp"),
                OperatorRoute.secret("/api/operator"),
                OperatorRoute.of("/api/operator/secrets"));
        var routes = new OperatorRoutes(List.of(feature));
        assertThat(routes.roots()).contains("/api/mcp", "/api/operator");
        assertThat(routes.sensitiveRoots()).containsExactly("/api/operator", "/api/operator/secrets");
        assertThat(routes.protectedServletPath("/api/mcp/servers")).isTrue();
        assertThat(routes.sensitive("/api/operator/status")).isTrue();
    }

    @Test
    void nullContributionsFailStartup() {
        assertThatThrownBy(() -> new OperatorRoutes(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OperatorRoutes(Collections.singletonList(null)))
                .isInstanceOf(NullPointerException.class);
        OperatorRouteContributor nullRoutes = () -> null;
        assertThatThrownBy(() -> new OperatorRoutes(List.of(nullRoutes)))
                .isInstanceOf(NullPointerException.class);
        OperatorRouteContributor nullRoute = () -> Collections.singletonList(null);
        assertThatThrownBy(() -> new OperatorRoutes(List.of(nullRoute)))
                .isInstanceOf(NullPointerException.class);
    }
}
