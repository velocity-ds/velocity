package net.velocity.agent.skills;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;

import net.velocity.ApplicationTime;
import net.velocity.agent.ToolRegistry;
import net.velocity.agent.spi.GatewayCustomizer;
import net.velocity.agent.spi.ToolProvider;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SkillsProperties.class)
public class SkillConfiguration {

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    SkillRepository skillRepository(ObjectProvider<MongoTemplate> mongo) {
        return new MongoSkillRepository(mongo.getObject());
    }

    @Bean
    @ConditionalOnMissingBean
    SkillService skillService(
            @Lazy SkillRepository repository,
            SkillsProperties properties,
            ObjectProvider<Clock> clock) {
        return new SkillService(
                repository,
                properties,
                clock.getIfAvailable(() -> Clock.system(ApplicationTime.zone())));
    }

    /** The skill tools (`skill_load`, `skill_patch` and the catalog calls), present only while skills are enabled. */
    @Bean
    @Order(60)
    ToolProvider skillToolProvider(SkillService skills, SkillsProperties properties) {
        return registry -> {
            if (properties.enabled()) {
                SkillTools.register(registry, skills);
            }
        };
    }

    /** Root tasks that loaded a skill get one extra turn to patch it when self-check is on and the tool exists. */
    @Bean
    @Order(700)
    GatewayCustomizer skillSelfCheck(SkillsProperties properties, ToolRegistry tools) {
        return gateway -> gateway.setSkillSelfCheck(
                properties.enabled() && properties.selfCheckEnabled()
                        && tools.find("skill_patch").isPresent());
    }
}
