package net.velocity.example;

import java.util.Map;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import net.velocity.agent.ToolRegistry;
import net.velocity.agent.spi.ContextContributor;
import net.velocity.agent.spi.SystemStatusContributor;
import net.velocity.agent.spi.ToolGuidanceContributor;
import net.velocity.agent.spi.ToolProvider;

/**
 * TEMPLATE: the one always-loaded configuration class of a feature module.
 *
 * <p>How it is found: {@code velocity-app} is a single {@code @SpringBootApplication} in {@code net.velocity},
 * whose component scan covers every jar on the classpath. A feature module therefore needs no registration in
 * core: keep its classes under {@code net.velocity.<feature>} and depend on the module from
 * {@code velocity-modules/velocity-app/pom.xml}. Nothing in {@code velocity-core} names this class.
 *
 * <p>How a disabled feature behaves: the configuration is always loaded, but a disabled feature contributes
 * nothing observable. The tool provider registers nothing when {@code example.enabled} is false, so the guidance
 * (which only applies when its tool is registered) is inert too, and the model never sees the feature. The
 * status section is the one deliberate exception: it is reported whether or not the feature is enabled, so
 * operators can tell "off" from "not installed".
 *
 * <p>Why every ordered bean has an explicit {@code @Order}: the order of tool providers, guidance and status
 * sections is observable (guidance text order is what the model reads; status key order is what operators and
 * clients see). Unordered beans run last in an unspecified-but-stable bean-definition order, which would let an
 * unrelated change reorder the output. Pick a value that is not already in the order tables of
 * {@code docs/agent-gateway.md} ("Wiring contributions") and add this feature's row there.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ExampleProperties.class)
public class ExampleConfiguration {

    /**
     * {@code ToolProvider}: called once while the {@code ToolRegistry} bean is built, after the core
     * registrations. The provider checks its own enabled flag; a duplicate tool name fails startup. Registering a
     * tool grants no authority: calls still pass the authorization policy, schema validation and scope checks.
     * Order 1100 is the next free value after the browser and credential tools (1000).
     */
    @Bean
    @Order(1100)
    ToolProvider exampleToolProvider(ExampleProperties properties) {
        return registry -> {
            if (properties.enabled()) {
                registry.register(ExampleTools.echo());
            }
        };
    }

    /**
     * {@code ContextContributor}: supplemental model instructions. {@code ToolGuidanceContributor.whenRegistered}
     * adds the text only when every named tool is registered, decided once at construction, so this is inert
     * while the tool is absent. Depending on the {@code ToolRegistry} guarantees the registry (and so every tool
     * provider) is complete before the check. Order 1100 follows the browser page-reading guidance (1000) and
     * precedes the browser session guidance (2000); the order is the order the model reads the guidance.
     */
    @Bean
    @Order(1100)
    ContextContributor exampleGuidance(ToolRegistry tools) {
        return ToolGuidanceContributor
                .whenRegistered(tools, ExamplePrompts.ECHO_INSTRUCTIONS, ExampleTools.ECHO);
    }

    /**
     * {@code SystemStatusContributor}: one top-level section of {@code GET /api/operator/status}. Status key order
     * is part of the API, so the order places this section after the last core/feature section
     * ({@code codeExecution}, 1400), and the key exists only while this module is on the classpath. The section
     * is evaluated per request and may return only safe flags and counts.
     */
    @Bean
    @Order(1500)
    SystemStatusContributor exampleStatus(ExampleProperties properties) {
        return SystemStatusContributor.of("example", () -> Map.of("enabled", properties.enabled()));
    }
}
