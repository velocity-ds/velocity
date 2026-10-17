package net.velocity.agent.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

import net.velocity.agent.spi.OperatorRouteContributor;

/**
 * Builds the protected-route set and its startup audit. Neither bean is replaceable: a missing or substituted
 * route set would silently remove the local operator boundary. Both are eager even when lazy initialization is
 * enabled, so the audit always runs at startup.
 */
@Configuration(proxyBeanMethods = false)
public class OperatorRoutesConfiguration {

    /** The core roots plus every {@link OperatorRouteContributor} bean; fails startup on an invalid root. */
    @Bean
    @Lazy(false)
    OperatorRoutes operatorRoutes(ObjectProvider<OperatorRouteContributor> contributors) {
        return new OperatorRoutes(contributors.orderedStream().toList());
    }

    /** Fails startup when a request mapping under /api is outside every protected root; see the audit class. */
    @Bean
    @Lazy(false)
    OperatorRouteAudit operatorRouteAudit(
            OperatorRoutes routes,
            ObjectProvider<RequestMappingInfoHandlerMapping> mappings) {
        return new OperatorRouteAudit(routes, mappings);
    }
}
