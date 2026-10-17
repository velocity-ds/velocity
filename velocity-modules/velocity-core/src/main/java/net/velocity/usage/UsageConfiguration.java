package net.velocity.usage;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.mongodb.core.MongoTemplate;

import net.velocity.agent.spi.UsageActivityContributor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UsageProperties.class)
public class UsageConfiguration {

    /**
     * The core activity names plus every {@link UsageActivityContributor}. Eager even under lazy initialization so
     * an invalid contribution always fails startup.
     */
    @Bean
    @Lazy(false)
    UsageActivities usageActivities(ObjectProvider<UsageActivityContributor> contributors) {
        return new UsageActivities(contributors.orderedStream().toList());
    }

    @Bean
    UsageRepository usageRepository(MongoTemplate mongo, UsageActivities activities) {
        return new MongoUsageRepository(mongo, activities);
    }

    @Bean
    UsageLedger usageLedger(
            UsageRepository repository,
            UsageProperties properties,
            Clock clock,
            UsageActivities activities) {
        return new UsageLedger(repository, properties, clock, activities);
    }

    @Bean
    UsageReports usageReports(UsageRepository repository, Clock clock, UsageActivities activities) {
        return new UsageReports(repository, clock, activities);
    }
}
