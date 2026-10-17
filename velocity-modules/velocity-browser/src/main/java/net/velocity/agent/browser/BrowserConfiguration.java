package net.velocity.agent.browser;

import java.time.Clock;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import net.velocity.agent.AuthorizationPolicy;
import net.velocity.agent.ToolRegistry;
import net.velocity.agent.persistence.ExecutionRepository;
import net.velocity.agent.research.ResearchService;
import net.velocity.agent.secrets.CredentialService;
import net.velocity.agent.security.BrowserDestinationPolicy;
import net.velocity.agent.security.OperatorRoutes;
import net.velocity.agent.security.ReservedDestinations;
import net.velocity.agent.spi.ContextContributor;
import net.velocity.agent.spi.SystemStatusContributor;
import net.velocity.agent.spi.ToolGuidanceContributor;
import net.velocity.agent.spi.ToolProvider;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BrowserProperties.class)
public class BrowserConfiguration {

    public record BrowserStatus(
            boolean enabled,
            boolean registered,
            String availability,
            String engine,
            String version,
            List<String> supportedTools,
            boolean headless) {}

    /** The browser section of the operator status document; reported whether or not the browser is enabled. */
    @Bean
    @Order(600)
    SystemStatusContributor browserStatus(BrowserProperties browser, ToolRegistry tools) {
        return SystemStatusContributor.of("browser", () -> {
            boolean registered = BrowserTools.coreToolNames()
                    .stream().allMatch(name -> tools.find(name).isPresent());
            return new BrowserStatus(
                    browser.enabled(),
                    registered,
                    !browser.enabled() ? "disabled" : registered ? "not_checked" : "unavailable",
                    "Browser Use",
                    BrowserService.VERSION,
                    BrowserTools.supportedToolNames(),
                    browser.headless());
        });
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.browser", name = "enabled", havingValue = "true")
    BrowserDestinationPolicy browserDestinationPolicy(
            BrowserProperties properties,
            OperatorRoutes routes,
            ReservedDestinations reserved) {
        return new BrowserDestinationPolicy(
                properties.allowedHosts(),
                properties.privateOrigins(),
                routes,
                reserved);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.browser", name = "enabled", havingValue = "true")
    BrowserService browserService(
            BrowserProperties properties,
            BrowserDestinationPolicy policy,
            Clock clock,
            ExecutionRepository repository,
            ObjectProvider<CredentialService> credentials) {
        return new BrowserService(properties, policy, clock, repository, credentials.getIfAvailable());
    }

    /**
     * Keeps sessions through question pauses and releases them after terminal execution. Present exactly when the
     * browser service is, so a disabled browser adds no guidance and no lifecycle hook. The context-hook order is
     * after the registered-tool guidance (see {@code ComposedContextHooks}).
     */
    @Bean
    @Order(2000)
    @ConditionalOnProperty(prefix = "agent.browser", name = "enabled", havingValue = "true")
    ContextContributor browserSessionContributor(BrowserService browser) {
        return new BrowserSessionContributor(browser);
    }

    @Bean
    @Order(1000)
    ContextContributor browserPageReadingGuidance(ToolRegistry tools) {
        return ToolGuidanceContributor
                .whenRegistered(tools, BrowserPrompts.BROWSER_PAGE_READING_INSTRUCTIONS, "browser_read_page");
    }

    @Bean
    @Order(1050)
    ContextContributor browserScreenshotGuidance(ToolRegistry tools) {
        return ToolGuidanceContributor
                .whenRegistered(tools, BrowserPrompts.BROWSER_SCREENSHOT_INSTRUCTIONS, ScreenshotTools.NAME);
    }

    @Bean
    @Order(1000)
    ToolProvider browserToolProvider(
            ObjectProvider<BrowserService> browsers,
            AuthorizationPolicy authorization,
            ResearchService research,
            ExecutionRepository executions,
            Clock clock) {
        return registry -> {
            BrowserService browser = browsers.getIfAvailable();
            if (browser != null) {
                BrowserTools.register(registry, browser);
                CredentialTools.register(registry, browser);
                ScreenshotTools.register(registry, browser, research, executions, clock);
                browser.setCredentialAuthorization(authorization, registry);
            }
        };
    }
}
