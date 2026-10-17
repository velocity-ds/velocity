package net.velocity.example;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * TEMPLATE: the feature's operator settings.
 *
 * <p>Conventions every feature follows:
 *
 * <ul>
 *   <li>One {@code @ConfigurationProperties} record per feature, bound under the feature's own prefix
 *       ({@code example}). It is enabled by {@code @EnableConfigurationProperties} on the feature's
 *       configuration class ({@link ExampleConfiguration}); there is no properties scan.
 *   <li>The record declares no defaults (no {@code @DefaultValue}). The default lives only in
 *       {@code velocity-modules/velocity-app/src/main/resources/application.properties} as {@code ${EXAMPLE_ENABLED:false}}, with a
 *       matching entry in {@code additional-spring-configuration-metadata.json}.
 *   <li>A feature is off by default and must contribute nothing observable while it is off.
 * </ul>
 */
@ConfigurationProperties("example")
public record ExampleProperties(boolean enabled) {}
