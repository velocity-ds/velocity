package net.velocity.operator;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.data.mongodb.core.MongoTemplate;

import net.velocity.ApplicationTime;
import net.velocity.agent.config.AgentProperties;
import net.velocity.agent.scheduling.SchedulingProperties;
import net.velocity.agent.secrets.CredentialService;
import net.velocity.agent.spi.SystemStatusContributor;
import net.velocity.agent.state.TrustedScope;

/**
 * The core sections of the operator status document. Features add theirs with their own
 * {@link SystemStatusContributor} beans. The {@code @Order} values interleave: 100 generatedAt, 200 timezone,
 * 300 localScope, 400 inference, 500 slack, 600 browser, 700 scheduling, 1000 secrets, 1100 mongo,
 * 1200 executionLimits, 1300 automaticResumption, 1400 codeExecution; features pick free values in between.
 */
@Configuration(proxyBeanMethods = false)
public class OperatorStatusConfiguration {

    @Bean
    @Order(100)
    SystemStatusContributor generatedAtStatus(Clock clock) {
        return SystemStatusContributor.of("generatedAt", clock::instant);
    }

    @Bean
    @Order(200)
    SystemStatusContributor timezoneStatus() {
        return SystemStatusContributor.of("timezone", ApplicationTime.zone()::getId);
    }

    @Bean
    @Order(300)
    SystemStatusContributor localScopeStatus() {
        return SystemStatusContributor
                .of(
                        "localScope",
                        () -> new TrustedScope("local", "operator", "operator", "velocity", "local"));
    }

    @Bean
    @Order(400)
    SystemStatusContributor inferenceStatus(OperatorInferenceController inference) {
        return SystemStatusContributor.of("inference", inference::capabilities);
    }

    @Bean
    @Order(700)
    SystemStatusContributor schedulingStatus(SchedulingProperties scheduling, Environment environment) {
        return SystemStatusContributor.of("scheduling", () -> {
            boolean workerEnabled = environment
                    .getProperty("jobrunr.background-job-server.enabled", Boolean.class, false);
            return new OperatorStatusController.Scheduling(
                    scheduling.enabled(),
                    workerEnabled,
                    workerEnabled ? "not_checked" : "disabled",
                    scheduling.maximumLateness().toString());
        });
    }

    @Bean
    @Order(1000)
    SystemStatusContributor secretsStatus(
            ObjectProvider<CredentialService> secrets,
            Environment environment) {
        return SystemStatusContributor.of("secrets", () -> {
            // Only availability flags are exposed: never key IDs, key material, labels or credential counts.
            var service = secrets.getIfAvailable();
            if (service == null) {
                return new OperatorStatusController.Secrets(false, false);
            }
            try {
                var availability = service.availability();
                return new OperatorStatusController.Secrets(availability.enabled(), availability.available());
            } catch (RuntimeException unavailable) {
                return new OperatorStatusController.Secrets(
                        environment.getProperty("agent.secrets.enabled", Boolean.class, false),
                        false);
            }
        });
    }

    @Bean
    @Order(1100)
    SystemStatusContributor mongoStatus(ObjectProvider<MongoTemplate> mongo) {
        return SystemStatusContributor.of(
                "mongo",
                () -> new OperatorStatusController.Mongo(
                        mongo.getIfAvailable() != null,
                        "not_checked",
                        null));
    }

    @Bean
    @Order(1200)
    SystemStatusContributor executionLimitsStatus(AgentProperties agent) {
        return SystemStatusContributor.of("executionLimits", () -> agent.limits().executionLimits());
    }

    @Bean
    @Order(1300)
    SystemStatusContributor automaticResumptionStatus(AgentProperties agent) {
        return SystemStatusContributor.of("automaticResumption", agent::automaticResumption);
    }
}
