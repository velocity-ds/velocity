package net.velocity.agent.monitoring;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservationFingerprintTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    @Test
    void factOrderWhitespaceUnicodeFormAndNumericFormDoNotChangeTheFingerprint() {
        var first = new LinkedHashMap<String, JsonNode>();
        first.put("price", JSON.numberNode(new BigDecimal("10.00")));
        first.put("title", JSON.textNode("  Café   Widget "));
        var second = new LinkedHashMap<String, JsonNode>();
        second.put("title", JSON.textNode("Café Widget"));
        second.put("price", JSON.numberNode(10));

        assertThat(ObservationFingerprint.fingerprint(first))
                .matches("[0-9a-f]{64}")
                .isEqualTo(ObservationFingerprint.fingerprint(second));
    }

    @Test
    void nestedStructuresAreNormalizedButArrayOrderIsSignificant() {
        var inner = JSON.objectNode();
        inner.put("y", 1);
        inner.put("x", 2);
        var reordered = JSON.objectNode();
        reordered.put("x", 2);
        reordered.put("y", 1);
        assertThat(ObservationFingerprint.fingerprint(Map.of("a", JSON.arrayNode().add(inner))))
                .isEqualTo(ObservationFingerprint.fingerprint(Map.of("a", JSON.arrayNode().add(reordered))));
        assertThat(ObservationFingerprint.fingerprint(Map.of("a", JSON.arrayNode().add(1).add(2))))
                .isNotEqualTo(
                        ObservationFingerprint.fingerprint(Map.of("a", JSON.arrayNode().add(2).add(1))));
        assertThat(ObservationFingerprint.fingerprint(Map.of("a", JSON.textNode("x"))))
                .isNotEqualTo(ObservationFingerprint.fingerprint(Map.of("b", JSON.textNode("x"))));
    }

    @Test
    void factsMustBePresentDistinctAfterNormalizationAndOfSupportedTypes() {
        assertThatThrownBy(() -> ObservationFingerprint.fingerprint(Map.of()))
                .hasMessage("Observation facts are required");
        assertThatThrownBy(() -> ObservationFingerprint.fingerprint(null))
                .hasMessage("Observation facts are required");
        var colliding = new LinkedHashMap<String, JsonNode>();
        colliding.put("a", JSON.textNode("1"));
        colliding.put("a ", JSON.textNode("2"));
        assertThatThrownBy(() -> ObservationFingerprint.fingerprint(colliding))
                .hasMessage("Fact names collide after normalization");
        assertThatThrownBy(
                () -> ObservationFingerprint.fingerprint(Map.of("blob", JSON.binaryNode(new byte[]{1}))))
                .hasMessage("Unsupported fact value");
        assertThat(
                ObservationFingerprint
                        .fingerprint(Map.of("missing", JSON.nullNode(), "flag", JSON.booleanNode(true))))
                .matches("[0-9a-f]{64}");
    }

    @Test
    void itemKeysAndVersionIdsAreStableNormalizedIdentities() {
        assertThat(ObservationFingerprint.itemKey("src", "item"))
                .matches("[0-9a-f]{64}")
                .isEqualTo(ObservationFingerprint.itemKey(" src ", "item"))
                .isNotEqualTo(ObservationFingerprint.itemKey("src", "item2"));
        assertThatThrownBy(() -> ObservationFingerprint.itemKey("src", " "))
                .hasMessage("Stable identity is required");
        assertThatThrownBy(() -> ObservationFingerprint.itemKey(null, "item"))
                .hasMessage("Stable identity is required");

        Map<String, JsonNode> facts = Map.of("price", JSON.numberNode(10));
        assertThat(ObservationFingerprint.fingerprint("src", "item", facts))
                .isNotEqualTo(ObservationFingerprint.fingerprint(facts))
                .isEqualTo(ObservationFingerprint.fingerprint(" src", "item ", facts));

        String version = ObservationFingerprint.versionId("job", "run", "src", "item", "fp");
        assertThat(version).matches("obs-[0-9a-f]{64}");
        assertThat(version).isEqualTo(ObservationFingerprint.versionId("job", "run", "src", "item", "fp"));
        assertThat(version)
                .isNotEqualTo(ObservationFingerprint.versionId("job", "run2", "src", "item", "fp"));
    }
}
