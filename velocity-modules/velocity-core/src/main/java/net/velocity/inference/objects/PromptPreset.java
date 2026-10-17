package net.velocity.inference.objects;

import net.velocity.inference.SystemPrompts;

/** Explicit task instructions, independent of tools, reasoning, and output format. */
public enum PromptPreset {

    STRUCTURED_EXTRACTION(SystemPrompts.STRUCTURED_EXTRACTION), WEB_RESEARCH(
            SystemPrompts.WEB_RESEARCH), SPECIFICATION_REVIEW(
                    SystemPrompts.SPECIFICATION_REVIEW), COMPUTER_USE(SystemPrompts.COMPUTER_USE);

    private final String prompt;

    PromptPreset(String prompt) {
        this.prompt = prompt;
    }

    public String prompt() {
        return prompt;
    }
}
