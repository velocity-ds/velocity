package net.velocity.agent.http;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StrictRequestsTest {

    @Test
    void duplicateKeysAreRejected() {
        assertThatThrownBy(() -> StrictRequests.object("{\"a\":1,\"a\":2}", StrictRequests.Numbers.STANDARD))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> StrictRequests.tree("{\"a\":{\"b\":1,\"b\":2}}"))
                .isInstanceOf(IOException.class);
    }

    @Test
    void trailingContentIsRejected() {
        assertThatThrownBy(
                () -> StrictRequests.object("{\"a\":1} {\"b\":2}", StrictRequests.Numbers.STANDARD))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> StrictRequests.object("{\"a\":1} x", StrictRequests.Numbers.STANDARD))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> StrictRequests.tree("[1] 2")).isInstanceOf(IOException.class);
    }

    @Test
    void objectsPreserveFieldOrderAndNumberPrecisionOnRequest() throws IOException {
        Map<String, Object> standard = StrictRequests.object(
                "{\"z\":1.10,\"a\":2}",
                StrictRequests.Numbers.STANDARD);
        assertThat(standard.keySet()).containsExactly("z", "a");
        assertThat(standard.get("z")).isEqualTo(1.1d);

        Map<String, Object> precise = StrictRequests.object(
                "{\"z\":1.10}".getBytes(StandardCharsets.UTF_8),
                StrictRequests.Numbers.PRECISE_FLOATS);
        assertThat(precise.get("z")).isEqualTo(new BigDecimal("1.10"));
    }

    @Test
    void unknownFieldsAreNamedAndNonObjectsRejected() throws IOException {
        var node = StrictRequests.tree("{\"text\":\"x\",\"extra\":1}");
        assertThatThrownBy(() -> StrictRequests.fields(node, Set.of("text")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown request field: extra");
        assertThatCode(() -> StrictRequests.fields(node, Set.of("text", "extra"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> StrictRequests.fields(StrictRequests.tree("[]"), Set.of()))
                .hasMessage("Expected a JSON object");
        assertThatThrownBy(
                () -> StrictRequests.fields((com.fasterxml.jackson.databind.JsonNode) null, Set.of()))
                .hasMessage("Expected a JSON object");
    }

    @Test
    void mapFieldsRequireKnownKeysRequiredKeysAndNonNullValues() {
        var values = new HashMap<String, Object>();
        values.put("a", 1);
        assertThatCode(() -> StrictRequests.fields(values, Set.of("a", "b"), Set.of("a")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> StrictRequests.fields(values, Set.of("a"), Set.of("a", "b")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StrictRequests.fields(values, Set.of("b"), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        values.put("b", null);
        assertThatThrownBy(() -> StrictRequests.fields(values, Set.of("a", "b"), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StrictRequests.fields((Map<String, Object>) null, Set.of(), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void queryParametersMustBeKnownAndSingleValued() {
        assertThatCode(() -> StrictRequests.parameters(Map.of("limit", new String[]{"5"}), Set.of("limit")))
                .doesNotThrowAnyException();
        assertThatThrownBy(
                () -> StrictRequests.parameters(Map.of("other", new String[]{"5"}), Set.of("limit")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                () -> StrictRequests.parameters(Map.of("limit", new String[]{"5", "6"}), Set.of("limit")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> StrictRequests.noParameters(Map.of(), "none")).doesNotThrowAnyException();
        assertThatThrownBy(() -> StrictRequests.noParameters(Map.of("a", "b"), "none")).hasMessage("none");
        assertThatCode(() -> StrictRequests.noQuery(null, "none")).doesNotThrowAnyException();
        assertThatThrownBy(() -> StrictRequests.noQuery("", "none")).hasMessage("none");
    }

    @Test
    void bodiesAreBoundedInBytesAndCharacters() throws IOException {
        byte[] payload = "héllo".getBytes(StandardCharsets.UTF_8);
        assertThat(StrictRequests.body(new ByteArrayInputStream(payload), 6, 5)).isEqualTo(payload);
        assertThatThrownBy(() -> StrictRequests.body(new ByteArrayInputStream(payload), 5, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StrictRequests.body(new ByteArrayInputStream(payload), 6, 4))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StrictRequests.body(new ByteArrayInputStream(new byte[]{(byte) 0xff}), 6, 4))
                .isInstanceOf(IOException.class);
    }

    @Test
    void emptinessPoliciesDistinguishJsonAndUnicodeWhitespace() {
        assertThatCode(() -> StrictRequests.empty((String) null, StrictRequests.EmptyBody.STRICT, "m"))
                .doesNotThrowAnyException();
        assertThatCode(() -> StrictRequests.empty("", StrictRequests.EmptyBody.STRICT, "m"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> StrictRequests.empty(" ", StrictRequests.EmptyBody.STRICT, "m"))
                .hasMessage("m");
        assertThatCode(() -> StrictRequests.empty(" \t\r\n", StrictRequests.EmptyBody.JSON_WHITESPACE, "m"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> StrictRequests.empty(" ", StrictRequests.EmptyBody.JSON_WHITESPACE, "m"))
                .hasMessage("m");
        assertThatCode(() -> StrictRequests.empty(" ", StrictRequests.EmptyBody.UNICODE_WHITESPACE, "m"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> StrictRequests.empty("{}", StrictRequests.EmptyBody.UNICODE_WHITESPACE, "m"))
                .hasMessage("m");
        assertThatCode(() -> StrictRequests.empty(" \n".getBytes(StandardCharsets.UTF_8), "m"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> StrictRequests.empty("x".getBytes(StandardCharsets.UTF_8), "m"))
                .hasMessage("m");
    }

    @Test
    void strictlyEmptyStreamsMayNotContainASingleByte() {
        assertThatCode(() -> StrictRequests.strictlyEmpty(new ByteArrayInputStream(new byte[0]), "m"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> StrictRequests.strictlyEmpty(new ByteArrayInputStream(new byte[]{' '}), "m"))
                .hasMessage("m");
    }
}
