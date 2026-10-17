package net.velocity.agent;

import java.util.regex.Pattern;

/**
 * Detects tool-call markup that a model wrote as plain text instead of a native call. Local chat
 * templates (Qwen and Hermes style {@code <tool_call>} tags, {@code <function=...>} wrappers) emit it
 * when the request carries no tools, so the reporting turn must treat such text as unusable rather
 * than deliver it as the final answer.
 */
final class RawToolCallMarkup {

    private static final Pattern MARKUP = Pattern.compile(
            "<(?:\\|?tool_call\\b|/tool_call>|function_call\\b|function=|invoke\\s+name=|\\|python_tag\\|)",
            Pattern.CASE_INSENSITIVE);

    private RawToolCallMarkup() {
    }

    /** Whether the text is, or contains, a raw tool-call block. */
    static boolean present(String text) {
        return text != null && MARKUP.matcher(text).find();
    }
}
