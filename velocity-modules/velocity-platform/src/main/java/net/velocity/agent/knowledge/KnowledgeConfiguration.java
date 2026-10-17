package net.velocity.agent.knowledge;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;

import net.velocity.agent.ToolRegistry;
import net.velocity.agent.spi.ContextContributor;
import net.velocity.agent.spi.ToolGuidanceContributor;
import net.velocity.agent.spi.ToolProvider;
import net.velocity.inference.SystemPrompts;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KnowledgeProperties.class)
public class KnowledgeConfiguration {

    // Do not open MongoDB or initialize indexes merely to start an unavailable agent.
    @Bean
    @Lazy
    @ConditionalOnMissingBean
    KnowledgeRepository knowledgeRepository(ObjectProvider<MongoTemplate> mongo) {
        return new MongoKnowledgeRepository(mongo.getObject());
    }

    @Bean
    @ConditionalOnMissingBean
    KnowledgeService knowledgeService(@Lazy KnowledgeRepository repository) {
        return new KnowledgeService(repository);
    }

    /**
     * The stored-data tools (`data_save`, `data_update`, `data_delete`, `data_get`, `data_query`,
     * `data_aggregate`, `data_catalog`); the writes confirm with the requester while
     * `agent.knowledge.confirm-writes` is on.
     */
    @Bean
    @Order(20)
    ToolProvider knowledgeToolProvider(KnowledgeService knowledge, KnowledgeProperties properties) {
        return registry -> KnowledgeTools.register(registry, knowledge, properties.confirmWrites());
    }

    @Bean
    @Order(400)
    ContextContributor storageGuidance(ToolRegistry tools) {
        return ToolGuidanceContributor.whenRegistered(
                tools,
                SystemPrompts.STORAGE_INSTRUCTIONS,
                "data_save",
                "data_update",
                "data_delete",
                "data_get",
                "data_query",
                "data_aggregate",
                "data_catalog");
    }
}
