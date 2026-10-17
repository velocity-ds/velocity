package net.velocity.agent.accounts;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

@Configuration(proxyBeanMethods = false)
public class AccountConfiguration {

    @Bean
    @org.springframework.context.annotation.Lazy
    @ConditionalOnMissingBean
    AccountRepository accountRepository(MongoTemplate mongo) {
        return new MongoAccountRepository(mongo);
    }
}
