package net.velocity.agent.secrets;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.mongodb.core.MongoTemplate;

import net.velocity.agent.security.ReservedDestinations;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecretsProperties.class)
public class SecretsConfiguration {

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    CredentialRepository credentialRepository(ObjectProvider<MongoTemplate> mongo) {
        return new MongoCredentialRepository(mongo.getObject());
    }

    @Bean
    @ConditionalOnMissingBean
    CredentialCrypto credentialCrypto(SecretsProperties properties) {
        return new CredentialCrypto(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    CredentialService credentialService(
            @Lazy CredentialRepository repository,
            SecretsProperties properties,
            CredentialCrypto crypto,
            Clock clock,
            ReservedDestinations reserved) {
        return new CredentialService(repository, properties, crypto, clock, reserved);
    }
}
