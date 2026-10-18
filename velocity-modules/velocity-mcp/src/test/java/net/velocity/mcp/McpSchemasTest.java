package net.velocity.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.velocity.agent.JsonSchema;

import io.modelcontextprotocol.spec.McpSchema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class McpSchemasTest {

    @Test
    void registeredNamesArePrefixedSanitizedAndBounded() {
        assertThat(McpSchemas.registeredName("files", "read_file")).isEqualTo("mcp_files_read_file");
        assertThat(McpSchemas.registeredName("files", "list dir!")).isEqualTo("mcp_files_list_dir_");
        assertThat(McpSchemas.registeredName("files", "")).isEqualTo("mcp_files_tool");
        assertThat(McpSchemas.registeredName("files", null)).isEqualTo("mcp_files_tool");
        String bounded = McpSchemas.registeredName("srv", "x".repeat(200));
        assertThat(bounded).hasSize(McpSchemas.MAX_TOOL_NAME).startsWith("mcp_srv_");
    }

    @Test
    void textIsCleanedOfControlCharactersAndClippedWithoutSplittingSurrogates() {
        assertThat(McpSchemas.clean("  Does\u0007things\n\ttoo  ", 100)).isEqualTo("Does things\n\ttoo");
        assertThat(McpSchemas.clean(null, 10)).isEmpty();
        assertThat(McpSchemas.clean("abcdef", 3)).isEqualTo("abc");
        assertThat(McpSchemas.clip("ab😀", 3)).isEqualTo("ab");
        assertThat(McpSchemas.clip("ab😀", 4)).isEqualTo("ab😀");
        assertThat(McpSchemas.clip(null, 4)).isEmpty();
    }

    @Test
    void missingOrNonObjectSchemasFallBackToAnOpenObject() {
        assertThat(McpSchemas.importInput(null))
                .isEqualTo(new McpSchemas.Imported(Map.of("type", "object"), List.of()));
        assertThat(McpSchemas.importInput(Map.of("type", "array")))
                .isEqualTo(new McpSchemas.Imported(Map.of("type", "object"), List.of("(all arguments)")));
        assertThat(McpSchemas.importInput(Map.of("type", "mystery")))
                .isEqualTo(new McpSchemas.Imported(Map.of("type", "object"), List.of("(all arguments)")));
    }

    @Test
    void inexpressibleKeywordsAreDroppedAndUntypedArgumentsNamed() {
        var raw = new LinkedHashMap<String, Object>();
        raw.put("type", "object");
        raw.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        var properties = new LinkedHashMap<String, Object>();
        properties.put(
                "q",
                Map.of(
                        "type",
                        "string",
                        "pattern",
                        "^a",
                        "minLength",
                        1,
                        "format",
                        "uri",
                        "description",
                        "  Query\u0007 "));
        properties.put("n", Map.of("type", List.of("integer", "null"), "minimum", 0.0, "maximum", 10));
        properties.put("any", Map.of("description", "anything"));
        properties.put("ref", Map.of("$ref", "#/defs/x"));
        properties.put("mode", Map.of("enum", List.of("fast", "slow", "fast")));
        properties.put("ratio", Map.of("enum", List.of(1, 2.5)));
        properties.put("flag", Map.of("const", true));
        properties.put("mixed", Map.of("enum", List.of("a", 1)));
        properties.put(
                "tags",
                Map.of(
                        "type",
                        "array",
                        "items",
                        Map.of("type", "string"),
                        "maxItems",
                        5,
                        "uniqueItems",
                        true));
        properties.put(
                "nested",
                Map.of(
                        "properties",
                        Map.of("deep", Map.of("type", "boolean")),
                        "additionalProperties",
                        false));
        raw.put("properties", properties);
        raw.put("required", List.of("q", "q", "", 7, "n"));
        raw.put("additionalProperties", false);

        McpSchemas.Imported imported = McpSchemas.importInput(raw);

        assertThatCode(() -> JsonSchema.check(imported.schema())).doesNotThrowAnyException();
        assertThat(imported.untyped()).containsExactly("any", "ref", "mixed");
        assertThat(imported.schema()).containsEntry("type", "object")
                .containsEntry("required", List.of("q", "n"))
                .doesNotContainKeys("$schema", "additionalProperties");
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) imported.schema().get("properties");
        assertThat(fields).containsOnlyKeys("q", "n", "mode", "ratio", "flag", "tags", "nested");
        assertThat(fields.get("q"))
                .isEqualTo(Map.of("type", "string", "minLength", 1L, "description", "Query"));
        assertThat(fields.get("n")).isEqualTo(
                Map.of(
                        "type",
                        "integer",
                        "minimum",
                        new java.math.BigDecimal("0.0"),
                        "maximum",
                        new java.math.BigDecimal("10")));
        assertThat(fields.get("mode")).isEqualTo(Map.of("type", "string", "enum", List.of("fast", "slow")));
        assertThat(fields.get("ratio")).isEqualTo(Map.of("type", "number", "enum", List.of(1, 2.5)));
        assertThat(fields.get("flag")).isEqualTo(Map.of("type", "boolean"));
        assertThat(fields.get("tags"))
                .isEqualTo(Map.of("type", "array", "items", Map.of("type", "string"), "maxItems", 5L));
        assertThat(fields.get("nested")).isEqualTo(
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of("deep", Map.of("type", "boolean")),
                        "additionalProperties",
                        false));
    }

    @Test
    void contradictoryBoundsAndMismatchedEnumsAreDropped() {
        var imported = McpSchemas.importInput(
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of(
                                "s",
                                Map.of("type", "string", "minLength", 5, "maxLength", 2),
                                "x",
                                Map.of("type", "string", "enum", List.of(1, 2)),
                                "neg",
                                Map.of("type", "array", "minItems", -1))));
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) imported.schema().get("properties");
        assertThat(fields.get("s")).isEqualTo(Map.of("type", "string"));
        assertThat(fields.get("x")).isEqualTo(Map.of("type", "string"));
        assertThat(fields.get("neg")).isEqualTo(Map.of("type", "array"));
        assertThat(imported.untyped()).isEmpty();
    }

    @Test
    void descriptionsCarryTheOperatorPrefixCleanedServerTextAndUntypedArguments() {
        var tool = new McpSchema.Tool(
                "read_file",
                null,
                "  Reads a file\u0007 ",
                Map.of("type", "object"),
                null,
                null,
                null);
        assertThat(McpSchemas.description("files", tool, List.of()))
                .isEqualTo("[MCP server files] Reads a file");
        assertThat(McpSchemas.description("files", tool, List.of("path", "mode")))
                .isEqualTo("[MCP server files] Reads a file" + McpPrompts.untypedArguments("path, mode"));

        var titled = new McpSchema.Tool("t", "Titled tool", " ", Map.of("type", "object"), null, null, null);
        assertThat(McpSchemas.description("files", titled, List.of()))
                .isEqualTo("[MCP server files] Titled tool");
        var blank = new McpSchema.Tool("t", null, null, Map.of("type", "object"), null, null, null);
        assertThat(McpSchemas.description("files", blank, List.of()))
                .isEqualTo("[MCP server files] " + McpPrompts.NO_DESCRIPTION);

        var verbose = new McpSchema.Tool(
                "t",
                null,
                "d".repeat(10_000),
                Map.of("type", "object"),
                null,
                null,
                null);
        assertThat(McpSchemas.description("files", verbose, List.of()))
                .hasSizeLessThanOrEqualTo(McpSchemas.MAX_DESCRIPTION_CHARS);
        var many = java.util.stream.IntStream.range(0, 25).mapToObj(index -> "arg" + index).toList();
        assertThat(McpSchemas.description("files", tool, many)).contains("arg19, ...")
                .doesNotContain("arg20");
    }
}
