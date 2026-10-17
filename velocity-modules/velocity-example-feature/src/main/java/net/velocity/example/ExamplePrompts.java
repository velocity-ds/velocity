package net.velocity.example;

/**
 * TEMPLATE: prompts owned by this feature. A feature keeps its model-visible text in its own
 * {@code <Feature>Prompts} class (core prompts live in {@code SystemPrompts}); text-block conventions follow
 * {@code SystemPrompts}.
 *
 * <p>Compatibility: a tool's description and schemas are saved in each task's admitted tool contract and compared
 * when the task resumes. Changing {@link #ECHO_DESCRIPTION} after release makes in-flight tasks that admitted the
 * tool fail to resume, so treat released tool text as a persisted contract.
 */
public final class ExamplePrompts {

    private ExamplePrompts() {
    }

    public static final String ECHO_DESCRIPTION = "Return the given text unchanged. A harmless read-only example tool that "
            + "demonstrates how a feature module contributes a tool; it has no side effects and needs no credentials.";

    public static final String ECHO_TEXT_DESCRIPTION = "Text to echo back, at most 200 characters.";

    public static final String ECHO_INSTRUCTIONS = """
            The example_echo tool returns the exact text it is given. Use it only when the requester explicitly asks
            to try the example tool; do not use it for any other purpose.
            """;
}
