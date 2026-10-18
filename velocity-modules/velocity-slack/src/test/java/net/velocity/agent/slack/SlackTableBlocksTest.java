package net.velocity.agent.slack;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import static org.assertj.core.api.Assertions.assertThat;

class SlackTableBlocksTest {

    private static final String FOOTER = "[{\"type\":\"context\",\"elements\":[{\"type\":\"mrkdwn\",\"text\":\"footer\"}]}]";

    @Test
    void textWithoutATableIsLeftAlone() {
        assertThat(SlackTableBlocks.render(null)).isNull();
        assertThat(SlackTableBlocks.render("   ")).isNull();
        assertThat(SlackTableBlocks.render("Just a paragraph with a | pipe.")).isNull();
        assertThat(SlackTableBlocks.render("| A | B |\n| 1 | 2 |")).isNull();
    }

    @Test
    void aPipeTableBecomesATableBlockWithAlignmentsAndEscapedPipes() {
        String rendered = SlackTableBlocks.render(
                "| Name | Qty | Note |\n|:---|---:|:---:|\n| Apple | 3 | fresh |\n| Pear \\| Fig | 10 | mixed |");

        JsonArray blocks = JsonParser.parseString(rendered).getAsJsonArray();
        assertThat(blocks).hasSize(1);
        JsonObject table = blocks.get(0).getAsJsonObject();
        assertThat(table.get("type").getAsString()).isEqualTo("table");
        JsonArray rows = table.getAsJsonArray("rows");
        assertThat(rows).hasSize(3);
        assertThat(cell(rows, 0, 0)).isEqualTo("Name");
        assertThat(cell(rows, 2, 0)).isEqualTo("Pear | Fig");
        assertThat(cell(rows, 2, 1)).isEqualTo("10");
        assertThat(rows.get(0).getAsJsonArray().get(0).getAsJsonObject().get("type").getAsString())
                .isEqualTo("raw_text");
        JsonArray settings = table.getAsJsonArray("column_settings");
        assertThat(settings.get(0).getAsJsonObject().get("align").getAsString()).isEqualTo("left");
        assertThat(settings.get(1).getAsJsonObject().get("align").getAsString()).isEqualTo("right");
        assertThat(settings.get(2).getAsJsonObject().get("align").getAsString()).isEqualTo("center");
        assertThat(settings.get(0).getAsJsonObject().get("is_wrapped").getAsBoolean()).isTrue();
    }

    @Test
    void surroundingTextBecomesSectionsAroundTheTable() {
        String rendered = SlackTableBlocks.render("Intro\n\n| A |\n|---|\n| 1 |\n\nOutro");

        JsonArray blocks = JsonParser.parseString(rendered).getAsJsonArray();
        assertThat(blocks).hasSize(3);
        assertThat(blocks.get(0).getAsJsonObject().get("type").getAsString()).isEqualTo("section");
        assertThat(blocks.get(0).getAsJsonObject().getAsJsonObject("text").get("text").getAsString())
                .isEqualTo("Intro\n\n");
        assertThat(blocks.get(1).getAsJsonObject().get("type").getAsString()).isEqualTo("table");
        assertThat(blocks.get(2).getAsJsonObject().getAsJsonObject("text").get("text").getAsString())
                .isEqualTo("\n\nOutro");
    }

    @Test
    void everyTableMustConvertWithinSlackLimitsOrNoneDoes() {
        assertThat(SlackTableBlocks.render("| A | B |\n|---|\n| 1 |")).isNull();
        assertThat(SlackTableBlocks.render("| A | B |\n|---|---|\n| 1 |")).isNull();
        var wide = new StringBuilder("|");
        var separator = new StringBuilder("|");
        for (int column = 0; column < 21; column++) {
            wide.append(" c").append(column).append(" |");
            separator.append("---|");
        }
        assertThat(SlackTableBlocks.render(wide + "\n" + separator)).isNull();
        var tall = new StringBuilder("| A |\n|---|\n");
        for (int row = 0; row < 101; row++) {
            tall.append("| ").append(row).append(" |\n");
        }
        assertThat(SlackTableBlocks.render(tall.toString())).isNull();
        assertThat(SlackTableBlocks.render("| A |\n|---|\n| 1 |\n\n| B | C |\n|---|\n| x |")).isNull();
    }

    @Test
    void fencedMarkdownTablesConvertButOtherCodeStaysLiteral() {
        String rendered = SlackTableBlocks.render("Report:\n```markdown\n| A |\n|---|\n| 1 |\n```\nDone");
        JsonArray blocks = JsonParser.parseString(rendered).getAsJsonArray();
        assertThat(blocks).hasSize(3);
        assertThat(blocks.get(1).getAsJsonObject().get("type").getAsString()).isEqualTo("table");
        assertThat(blocks.get(2).getAsJsonObject().getAsJsonObject("text").get("text").getAsString())
                .isEqualTo("\nDone");

        String withFilename = SlackTableBlocks.render("```\nsummary.md\n\n| A |\n|---|\n| 1 |\n```");
        JsonArray named = JsonParser.parseString(withFilename).getAsJsonArray();
        assertThat(named).hasSize(2);
        assertThat(named.get(0).getAsJsonObject().getAsJsonObject("text").get("text").getAsString())
                .isEqualTo("summary.md\n");
        assertThat(named.get(1).getAsJsonObject().get("type").getAsString()).isEqualTo("table");

        assertThat(SlackTableBlocks.render("```python\n| A |\n|---|\n| 1 |\n```")).isNull();
        assertThat(SlackTableBlocks.render("```\n| A |\n|---|\n| 1 |")).isNull();
    }

    @Test
    void renderingWithAFooterAlwaysProducesBlocks() {
        JsonArray plain = JsonParser.parseString(SlackTableBlocks.render("hello", FOOTER)).getAsJsonArray();
        assertThat(plain).hasSize(2);
        assertThat(plain.get(0).getAsJsonObject().getAsJsonObject("text").get("text").getAsString())
                .isEqualTo("hello");
        assertThat(plain.get(1).getAsJsonObject().get("type").getAsString()).isEqualTo("context");

        JsonArray tabled = JsonParser.parseString(SlackTableBlocks.render("| A |\n|---|\n| 1 |", FOOTER))
                .getAsJsonArray();
        assertThat(tabled).hasSize(2);
        assertThat(tabled.get(0).getAsJsonObject().get("type").getAsString()).isEqualTo("table");

        JsonArray split = JsonParser.parseString(SlackTableBlocks.render("a".repeat(7_000), FOOTER))
                .getAsJsonArray();
        assertThat(split).hasSize(4);
        assertThat(split.get(0).getAsJsonObject().getAsJsonObject("text").get("text").getAsString())
                .hasSize(3_000);
        assertThat(split.get(2).getAsJsonObject().getAsJsonObject("text").get("text").getAsString())
                .hasSize(1_000);
    }

    private static String cell(JsonArray rows, int row, int column) {
        return rows.get(row).getAsJsonArray().get(column).getAsJsonObject().get("text").getAsString();
    }
}
