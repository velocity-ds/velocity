package net.velocity.agent.monitoring;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;

import net.velocity.agent.AgentGateway;
import net.velocity.agent.SchedulerIngressGateway;
import net.velocity.agent.ToolRegistry;
import net.velocity.agent.scheduling.JobRunrQueue;
import net.velocity.agent.scheduling.ScheduleDelivery;
import net.velocity.agent.scheduling.ScheduleService;
import net.velocity.agent.spi.ContextContributor;
import net.velocity.agent.spi.GatewayCustomizer;
import net.velocity.agent.spi.ToolGuidanceContributor;
import net.velocity.agent.spi.ToolProvider;
import net.velocity.inference.SystemPrompts;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MonitoringProperties.class)
public class MonitoringConfiguration {

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    MonitoringRepository monitoringRepository(ObjectProvider<MongoTemplate> mongo) {
        return new MongoMonitoringRepository(mongo.getObject());
    }

    @Bean
    @ConditionalOnMissingBean
    MonitoringService monitoringService(
            @Lazy MonitoringRepository repository,
            ScheduleService schedules,
            ObjectProvider<AgentGateway> gateway,
            ObjectProvider<SchedulerIngressGateway> ingress,
            ObjectProvider<ScheduleDelivery> delivery,
            MonitoringProperties properties,
            Clock clock,
            org.springframework.core.env.Environment environment) {
        var service = new MonitoringService(
                repository,
                schedules,
                gateway::getObject,
                ingress::getObject,
                delivery.getIfAvailable(ScheduleDelivery::unavailable),
                properties,
                clock);
        service.setWorkerEnabled(JobRunrQueue.workerEnabled(environment));
        schedules.setMonitoringHandler(service);
        schedules.setMonitoringOccurrenceRetention(properties.retainedRuns());
        return service;
    }

    /** The monitoring tools (`monitor_create` and its management calls). */
    @Bean
    @Order(70)
    ToolProvider monitoringToolProvider(MonitoringService monitoring) {
        return registry -> MonitoringTools.register(registry, monitoring);
    }

    @Bean
    @Order(600)
    ContextContributor monitoringGuidance(ToolRegistry tools) {
        return ToolGuidanceContributor
                .whenRegistered(tools, SystemPrompts.MONITORING_INSTRUCTIONS, "monitor_create");
    }

    /** Installs the trusted verifier of a monitoring execution's complete saved association. */
    @Bean
    @Order(600)
    GatewayCustomizer monitoringExecutionValidator(ObjectProvider<MonitoringService> monitoring) {
        return gateway -> gateway
                .setMonitoringValidator(snapshot -> monitoring.getObject().verifyExecution(snapshot));
    }
}
