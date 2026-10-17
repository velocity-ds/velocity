package net.velocity.agent.memory;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;

import net.velocity.agent.persistence.ExecutionRepository;
import net.velocity.agent.spi.ToolProvider;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MemoryProperties.class)
public class MemoryConfiguration {

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    MemoryRepository memoryRepository(ObjectProvider<MongoTemplate> mongo) {
        return new MongoMemoryRepository(mongo.getObject());
    }

    @Bean
    @ConditionalOnMissingBean
    MemoryService memoryService(
            @Lazy MemoryRepository repository,
            MemoryProperties properties,
            Clock clock,
            @Lazy ExecutionRepository executions) {
        return new MemoryService(repository, properties, clock, executions);
    }

    /** The memory tools (`memory_update` and its reads), present only while memory is enabled. */
    @Bean
    @Order(50)
    ToolProvider memoryToolProvider(MemoryService memory, MemoryProperties properties) {
        return registry -> {
            if (properties.enabled()) {
                MemoryTools.register(registry, memory);
            }
        };
    }
}
