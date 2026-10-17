package net.velocity.agent.tools;

import java.util.List;
import java.util.Map;

import net.velocity.agent.ToolRegistry;
import net.velocity.inference.SystemPrompts;

/** The conversation runner records the question and pauses for the requester's answer. */
public final class AskUserTool {

    private AskUserTool() {
    }

    public static ToolRegistry.Definition definition() {
        return new ToolRegistry.Definition(
                "ask_user",
                SystemPrompts.ASK_USER_DESCRIPTION,
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of("question", Map.of("type", "string", "minLength", 1)),
                        "required",
                        List.of("question"),
                        "additionalProperties",
                        false),
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of("waiting", Map.of("type", "boolean")),
                        "required",
                        List.of("waiting"),
                        "additionalProperties",
                        false),
                "agent.question",
                true,
                true,
                false,
                0,
                (context, input) -> Map.of("waiting", true));
    }
}
