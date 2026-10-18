package net.velocity.agent;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonSchemaTest {

    private static final Map<String, Object> PERSON = Map.of(
            "type",
            "object",
            "properties",
            Map.of(
                    "name",
                    Map.of("type", "string", "minLength", 1, "maxLength", 3),
                    "age",
                    Map.of("type", "integer", "minimum", 0, "maximum", 150),
                    "tags",
                    Map.of("type", "array", "items", Map.of("type", "string"), "maxItems", 2),
                    "role",
                    Map.of("type", "string", "enum", List.of("admin", "user"))),
            "required",
            List.of("name"),
            "additionalProperties",
            false);

    @Test
    void acceptsTheBoundedDialect() {
        assertThatCode(() -> JsonSchema.check(PERSON)).doesNotThrowAnyException();
        assertThatCode(() -> JsonSchema.check(Map.of("type", "null"))).doesNotThrowAnyException();
        assertThatCode(
                () -> JsonSchema.check(
                        Map.of("type", "object", "additionalProperties", Map.of("type", "number"))))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsUnsupportedKeywords() {
        assertThatThrownBy(() -> JsonSchema.check(Map.of("type", "string", "pattern", "^a$")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported tool schema keyword");
    }

    @Test
    void requiresASupportedType() {
        assertThatThrownBy(() -> JsonSchema.check(Map.of("description", "x")))
                .hasMessage("Tool schema requires a supported type");
        assertThatThrownBy(() -> JsonSchema.check(Map.of("type", "date")))
                .hasMessage("Tool schema requires a supported type");
        assertThatThrownBy(() -> JsonSchema.check(null))
                .hasMessage("Invalid or deeply nested tool schema");
    }

    @Test
    void rejectsMalformedConstraints() {
        assertThatThrownBy(() -> JsonSchema.check(Map.of("type", "object", "required", List.of("a", "a"))))
                .hasMessage("Tool schema required must contain distinct property names");
        assertThatThrownBy(() -> JsonSchema.check(Map.of("type", "string", "minLength", -1)))
                .hasMessage("Tool schema minLength must be a nonnegative integer");
        assertThatThrownBy(() -> JsonSchema.check(Map.of("type", "string", "maxLength", 1.5)))
                .hasMessage("Tool schema maxLength must be a nonnegative integer");
        assertThatThrownBy(() -> JsonSchema.check(Map.of("type", "number", "minimum", 5, "maximum", 1)))
                .hasMessage("Tool schema minimum exceeds maximum");
        assertThatThrownBy(() -> JsonSchema.check(Map.of("type", "string", "enum", List.of())))
                .hasMessage("Tool schema enum must be a nonempty list");
        assertThatThrownBy(() -> JsonSchema.check(Map.of("type", "number", "enum", List.of(1, 1.0))))
                .hasMessage("Tool schema enum contains duplicate values");
        assertThatThrownBy(() -> JsonSchema.check(Map.of("type", "string", "description", 7)))
                .hasMessage("Tool schema description must be a string");
    }

    @Test
    void rejectsDeepNesting() {
        Map<String, Object> schema = Map.of("type", "string");
        for (int depth = 0; depth < 70; depth++) {
            schema = Map.of("type", "array", "items", schema);
        }
        Map<String, Object> deep = schema;
        assertThatThrownBy(() -> JsonSchema.check(deep)).hasMessage("Invalid or deeply nested tool schema");
    }

    @Test
    void validatesConformingValues() {
        assertThatCode(
                () -> JsonSchema.validate(
                        PERSON,
                        Map.of("name", "Ann", "age", 30, "tags", List.of("a"), "role", "user")))
                .doesNotThrowAnyException();
        // Integers may arrive as floating-point numbers without a fraction.
        assertThatCode(() -> JsonSchema.validate(PERSON, Map.of("name", "Ann", "age", 30.0)))
                .doesNotThrowAnyException();
        assertThatCode(
                () -> JsonSchema.validate(PERSON, Map.of("name", "Ann", "age", new BigDecimal("30.000"))))
                .doesNotThrowAnyException();
    }

    @Test
    void stringLengthsCountCodePoints() {
        String threeEmoji = "😀😀😀";
        assertThatCode(() -> JsonSchema.validate(PERSON, Map.of("name", threeEmoji)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> JsonSchema.validate(PERSON, Map.of("name", threeEmoji + "😀")))
                .hasMessage("Tool value outside bounds");
    }

    @Test
    void rejectsNonConformingValues() {
        assertThatThrownBy(() -> JsonSchema.validate(PERSON, Map.of("age", 30)))
                .hasMessage("Missing tool property");
        assertThatThrownBy(() -> JsonSchema.validate(PERSON, Map.of("name", "Ann", "extra", 1)))
                .hasMessage("Unexpected tool property");
        assertThatThrownBy(() -> JsonSchema.validate(PERSON, Map.of("name", "Ann", "age", 30.5)))
                .hasMessage("Tool value has incorrect type");
        assertThatThrownBy(() -> JsonSchema.validate(PERSON, Map.of("name", "Ann", "age", 151)))
                .hasMessage("Tool value outside bounds");
        assertThatThrownBy(
                () -> JsonSchema.validate(PERSON, Map.of("name", "Ann", "tags", List.of("a", "b", "c"))))
                .hasMessage("Tool value outside bounds");
        assertThatThrownBy(() -> JsonSchema.validate(PERSON, Map.of("name", "Ann", "tags", List.of(1))))
                .hasMessage("Tool value has incorrect type");
        assertThatThrownBy(() -> JsonSchema.validate(PERSON, Map.of("name", "Ann", "role", "guest")))
                .hasMessage("Tool value outside enum");
        assertThatThrownBy(() -> JsonSchema.validate(PERSON, List.of()))
                .hasMessage("Tool value has incorrect type");
    }

    @Test
    void additionalPropertySchemasApplyToUnknownKeys() {
        Map<String, Object> schema = Map
                .of("type", "object", "additionalProperties", Map.of("type", "integer"));
        assertThatCode(() -> JsonSchema.validate(schema, Map.of("a", 1, "b", 2))).doesNotThrowAnyException();
        assertThatThrownBy(() -> JsonSchema.validate(schema, Map.of("a", "x")))
                .hasMessage("Tool value has incorrect type");
    }

    @Test
    void rejectsValuesThatAreNotJsonData() {
        Map<String, Object> schema = Map.of("type", "object");
        assertThatThrownBy(() -> JsonSchema.validate(schema, Map.of("a", new Object())))
                .hasMessage("Tool value is not JSON data");
        var nonStringKeys = new HashMap<Object, Object>();
        nonStringKeys.put(1, "x");
        assertThatThrownBy(() -> JsonSchema.validate(schema, nonStringKeys))
                .hasMessage("JSON object keys must be strings");
        assertThatThrownBy(() -> JsonSchema.validate(Map.of("type", "number"), Double.NaN))
                .hasMessage("Expected a finite JSON number");
    }
}
