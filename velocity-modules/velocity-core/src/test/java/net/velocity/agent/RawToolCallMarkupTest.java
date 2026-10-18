package net.velocity.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RawToolCallMarkupTest {

    @Test
    void detectsLocalChatTemplateMarkup() {
        assertThat(RawToolCallMarkup.present("<tool_call>{\"name\":\"x\"}</tool_call>")).isTrue();
        assertThat(RawToolCallMarkup.present("text then </tool_call>")).isTrue();
        assertThat(RawToolCallMarkup.present("<|tool_call|>")).isTrue();
        assertThat(RawToolCallMarkup.present("<function=search>{}</function>")).isTrue();
        assertThat(RawToolCallMarkup.present("<function_call>")).isTrue();
        assertThat(RawToolCallMarkup.present("<invoke name=\"search\">")).isTrue();
        assertThat(RawToolCallMarkup.present("<|python_tag|>print(1)")).isTrue();
        assertThat(RawToolCallMarkup.present("<TOOL_CALL>")).isTrue();
    }

    @Test
    void ignoresOrdinaryTextMentioningTools() {
        assertThat(RawToolCallMarkup.present(null)).isFalse();
        assertThat(RawToolCallMarkup.present("")).isFalse();
        assertThat(RawToolCallMarkup.present("Use the tool_call feature of the API")).isFalse();
        assertThat(RawToolCallMarkup.present("<tool_caller>")).isFalse();
        assertThat(RawToolCallMarkup.present("a < b and c > d")).isFalse();
    }
}
