package net.velocity.agent.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import net.velocity.agent.spi.ReservedDestinationPolicy;

/**
 * Builds the reserved-destination set. The bean is not replaceable (a substituted set could silently drop a
 * feature's restrictions) and is eager even when lazy initialization is enabled.
 */
@Configuration(proxyBeanMethods = false)
public class ReservedDestinationsConfiguration {

    /** Every {@link ReservedDestinationPolicy} bean, in Spring order. */
    @Bean
    @Lazy(false)
    ReservedDestinations reservedDestinations(ObjectProvider<ReservedDestinationPolicy> policies) {
        return new ReservedDestinations(policies.orderedStream().toList());
    }
}
