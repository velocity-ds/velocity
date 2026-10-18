package net.velocity.agent.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperatorPathsTest {

    @Test
    void collapsesDotSegmentsAndRepeatedSlashes() {
        assertThat(OperatorPaths.normalize("/api/operator/../agent")).isEqualTo("/api/agent");
        assertThat(OperatorPaths.normalize("/api//operator/./status")).isEqualTo("/api/operator/status");
        assertThat(OperatorPaths.normalize("/api/operator/")).isEqualTo("/api/operator");
        assertThat(OperatorPaths.normalize("")).isEqualTo("/");
        assertThat(OperatorPaths.normalize("/")).isEqualTo("/");
    }

    @Test
    void traversalCannotEscapeTheRoot() {
        assertThat(OperatorPaths.normalize("/../../etc/passwd")).isEqualTo("/etc/passwd");
        assertThat(OperatorPaths.normalize("/api/operator/%2e%2e/secrets")).isEqualTo("/api/secrets");
    }

    @Test
    void decodesPercentEncodingAndDropsPathParameters() {
        assertThat(OperatorPaths.normalize("/api/%6fperator")).isEqualTo("/api/operator");
        assertThat(OperatorPaths.normalize("/api/operator;jsessionid=abc/secrets"))
                .isEqualTo("/api/operator/secrets");
        assertThat(OperatorPaths.normalize("/api/operator/secrets;x=1")).isEqualTo("/api/operator/secrets");
    }

    @Test
    void invalidEncodingIsAnError() {
        assertThatThrownBy(() -> OperatorPaths.normalize("/api/%zz"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
