package net.velocity.example;

import java.util.Map;

import org.junit.jupiter.api.Test;

import net.velocity.agent.JsonSchema;
import net.velocity.agent.ToolRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TEMPLATE: a feature's unit tests exercise its trusted Java metadata and handlers directly, with no Spring
 * context, database or inference. They live under {@code src/test/java} in the feature module and run with
 * {@code sh ./mvnw test}.
 */
class ExampleToolsTest {

    private final ToolRegistry.Definition echo = ExampleTools.echo();

    @Test
    void theToolIsATrustedReadWithBoundedSchemas() {
        assertThat(echo.name()).isEqualTo(ExampleTools.ECHO).isEqualTo("example_echo");
        assertThat(echo.description()).isEqualTo(ExamplePrompts.ECHO_DESCRIPTION);
        assertThat(echo.category()).isEqualTo("system.read");
        assertThat(echo.trusted()).isTrue();
        assertThat(echo.scoped()).isFalse();
        assertThat(echo.cacheable()).isFalse();
        assertThat(echo.timeoutMillis()).isEqualTo(5_000);
        assertThat(echo.monitoringAccess()).isEqualTo(ToolRegistry.MonitoringAccess.observation);
        assertThatCode(() -> JsonSchema.check(echo.inputSchema())).doesNotThrowAnyException();
        assertThatCode(() -> JsonSchema.check(echo.outputSchema())).doesNotThrowAnyException();
    }

    @Test
    void inputIsValidatedAgainstTheDeclaredSchema() {
        assertThatCode(() -> JsonSchema.validate(echo.inputSchema(), Map.of("text", "hello")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> JsonSchema.validate(echo.inputSchema(), Map.of("text", "")))
                .hasMessage("Tool value outside bounds");
        assertThatThrownBy(() -> JsonSchema.validate(echo.inputSchema(), Map.of("text", "x".repeat(201))))
                .hasMessage("Tool value outside bounds");
        assertThatThrownBy(() -> JsonSchema.validate(echo.inputSchema(), Map.of("text", "hi", "loud", true)))
                .hasMessage("Unexpected tool property");
        assertThatThrownBy(() -> JsonSchema.validate(echo.inputSchema(), Map.of()))
                .hasMessage("Missing tool property");
    }

    @Test
    void theHandlerEchoesTheTextAndMatchesTheOutputSchema() throws Exception {
        Object output = echo.handler().execute(null, Map.of("text", "hello"));
        assertThat(output).isEqualTo(Map.of("text", "hello"));
        assertThatCode(() -> JsonSchema.validate(echo.outputSchema(), output)).doesNotThrowAnyException();
    }
}
