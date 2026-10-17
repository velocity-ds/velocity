package net.velocity.example;

import java.util.List;
import java.util.Map;

import net.velocity.agent.ToolRegistry;

/**
 * TEMPLATE: a tool is a {@link ToolRegistry.Definition}: trusted Java metadata plus a handler. Everything here is
 * decided by Java, never by the model:
 *
 * <ul>
 *   <li><b>Name.</b> {@code [a-zA-Z0-9_-]{1,64}}, unique across all tools (a duplicate fails startup). Tool names
 *       are permanent once released: they are stored in saved tasks and receipts.
 *   <li><b>Schemas.</b> Explicit input and output schemas in the bounded dialect of {@code JsonSchema}. Inputs are
 *       validated against the input schema before the handler runs. Bound every string.
 *   <li><b>Category.</b> {@code system.read} marks a read with no external or persisted effect. Reads may run
 *       without approval; the category also decides the default unattended (monitoring) access.
 *   <li><b>Flags.</b> {@code trusted} (registered by Java), {@code scoped} (needs the caller's
 *       {@code TrustedScope} and must enforce ownership itself; this tool touches no user resource, so it is not
 *       scoped) and {@code cacheable}.
 *   <li><b>Timeout.</b> Upper bound for one call in milliseconds, further capped by the task's remaining time.
 *   <li><b>Authority.</b> Registering the tool grants nothing. Every call still passes the
 *       {@code AuthorizationPolicy}, schema validation and scope checks.
 * </ul>
 */
final class ExampleTools {

    static final String ECHO = "example_echo";

    private static final int MAX_TEXT_CHARS = 200;

    private ExampleTools() {
    }

    static ToolRegistry.Definition echo() {
        return new ToolRegistry.Definition(
                ECHO,
                ExamplePrompts.ECHO_DESCRIPTION,
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of(
                                "text",
                                Map.of(
                                        "type",
                                        "string",
                                        "minLength",
                                        1,
                                        "maxLength",
                                        MAX_TEXT_CHARS,
                                        "description",
                                        ExamplePrompts.ECHO_TEXT_DESCRIPTION)),
                        "required",
                        List.of("text"),
                        "additionalProperties",
                        false),
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of("text", Map.of("type", "string")),
                        "required",
                        List.of("text"),
                        "additionalProperties",
                        false),
                "system.read",
                true,
                false,
                false,
                5_000,
                ToolRegistry.MonitoringAccess.observation,
                (context, input) -> Map.of("text", input.get("text")));
    }
}
