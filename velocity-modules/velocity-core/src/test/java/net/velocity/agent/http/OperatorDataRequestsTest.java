package net.velocity.agent.http;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperatorDataRequestsTest {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type",
            "object",
            "properties",
            Map.of(
                    "name",
                    Map.of("type", "string", "maxLength", 20),
                    "count",
                    Map.of("type", "integer", "minimum", 0),
                    "items",
                    Map.of("type", "array", "items", Map.of("type", "integer")),
                    "ratio",
                    Map.of("type", "number"),
                    "expected_revision",
                    Map.of("type", "integer")),
            "required",
            List.of("name"),
            "additionalProperties",
            false);

    @Test
    void readsValidBodiesAndNormalizesIntegersToLongs() {
        Map<String, Object> input = OperatorDataRequests.read(
                "{\"name\":\"x\",\"count\":2.0,\"items\":[1.0,2],\"ratio\":0.50}",
                SCHEMA);
        assertThat(input.get("name")).isEqualTo("x");
        assertThat(input.get("count")).isEqualTo(2L);
        assertThat(input.get("items")).isEqualTo(List.of(1L, 2L));
        // Free-form numbers keep their exact decimal representation.
        assertThat(input.get("ratio")).isEqualTo(new java.math.BigDecimal("0.50"));
    }

    @Test
    void rejectsUnknownFieldsMalformedJsonAndSchemaViolations() {
        assertThatThrownBy(() -> OperatorDataRequests.read("{\"name\":\"x\",\"extra\":1}", SCHEMA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unexpected tool property");
        assertThatThrownBy(() -> OperatorDataRequests.read("{\"name\":\"x\"", SCHEMA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid JSON request");
        assertThatThrownBy(() -> OperatorDataRequests.read("{\"name\":\"x\",\"name\":\"y\"}", SCHEMA))
                .hasMessage("Invalid JSON request");
        assertThatThrownBy(() -> OperatorDataRequests.read("{\"name\":\"x\",\"count\":2.5}", SCHEMA))
                .hasMessage("Tool value has incorrect type");
        assertThatThrownBy(() -> OperatorDataRequests.read("{\"count\":1}", SCHEMA))
                .hasMessage("Missing tool property");
        assertThatThrownBy(() -> OperatorDataRequests.read("[]", SCHEMA)).hasMessage("Invalid JSON request");
    }

    @Test
    void routesWithoutParametersRejectAnyQuery() {
        assertThatCode(() -> OperatorDataRequests.read("{\"name\":\"x\"}", Map.of(), SCHEMA))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> OperatorDataRequests.read("{\"name\":\"x\"}", Map.of("q", "1"), SCHEMA))
                .hasMessage("This route accepts no query parameters");
        assertThatThrownBy(() -> OperatorDataRequests.noParameters(Map.of("q", "1")))
                .hasMessage("This route accepts no query parameters");
    }

    @Test
    void revisionIsReadOnlyWhenPresentAsANumber() {
        assertThat(OperatorDataRequests.revision(Map.of("expected_revision", 3L))).isEqualTo(3L);
        assertThat(OperatorDataRequests.revision(Map.of("expected_revision", 4))).isEqualTo(4L);
        assertThat(OperatorDataRequests.revision(Map.of("name", "x"))).isNull();
        assertThat(OperatorDataRequests.revision(Map.of("expected_revision", "3"))).isNull();
    }

    @Test
    void narrowedSchemasKeepOnlyAllowedPropertiesAndRequirements() {
        Map<String, Object> narrowed = OperatorDataRequests.fields(SCHEMA, Set.of("count", "name"));
        assertThat(narrowed.get("type")).isEqualTo("object");
        assertThat(narrowed.get("additionalProperties")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) narrowed.get("properties");
        assertThat(properties).containsOnlyKeys("count", "name");
        assertThat(narrowed.get("required")).isEqualTo(List.of("name"));
        assertThat(OperatorDataRequests.fields(SCHEMA, Set.of("count")).get("required")).isEqualTo(List.of());
    }

    @Test
    void bodilessRoutesAcceptOnlyWhitespace() {
        assertThatCode(() -> OperatorDataRequests.noBody(null, Map.of())).doesNotThrowAnyException();
        assertThatCode(() -> OperatorDataRequests.noBody("  \n", Map.of())).doesNotThrowAnyException();
        assertThatThrownBy(() -> OperatorDataRequests.noBody("{}", Map.of()))
                .hasMessage("This route accepts no request body");
        assertThatThrownBy(() -> OperatorDataRequests.noBody("", Map.of("a", "b")))
                .hasMessage("This route accepts no query parameters");
    }
}
