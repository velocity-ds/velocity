package net.velocity.agent.scheduling;

import java.time.Clock;

import org.jobrunr.scheduling.JobScheduler;
import org.jobrunr.storage.StorageProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.data.mongodb.core.MongoTemplate;

import net.velocity.agent.AgentGateway;
import net.velocity.agent.SchedulerIngressGateway;
import net.velocity.agent.ToolRegistry;
import net.velocity.agent.spi.ContextContributor;
import net.velocity.agent.spi.GatewayCustomizer;
import net.velocity.agent.spi.ToolGuidanceContributor;
import net.velocity.agent.spi.ToolProvider;
import net.velocity.inference.SystemPrompts;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SchedulingProperties.class)
public class SchedulingConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ScheduleRepository scheduleRepository(MongoTemplate mongo) {
        return new MongoScheduleRepository(mongo);
    }

    @Bean
    @ConditionalOnMissingBean
    ScheduleJobs scheduleJobs(
            SchedulingProperties properties,
            Environment environment,
            ObjectProvider<JobScheduler> jobs,
            ObjectProvider<StorageProvider> storage) {
        boolean enabled = properties.enabled()
                && environment.getProperty("agent.scheduling.enabled", Boolean.class, false);
        return new JobRunrScheduleJobs(enabled, jobs::getIfAvailable, storage::getIfAvailable);
    }

    @Bean
    @ConditionalOnMissingBean
    ScheduleService scheduleService(
            ScheduleRepository repository,
            ScheduleJobs jobs,
            ObjectProvider<SchedulerIngressGateway> ingress,
            ObjectProvider<AgentGateway> gateway,
            ObjectProvider<ScheduleDelivery> delivery,
            Clock clock,
            SchedulingProperties properties) {
        return new ScheduleService(
                repository,
                jobs,
                ingress::getObject,
                gateway::getObject,
                delivery.getIfAvailable(ScheduleDelivery::unavailable),
                clock,
                properties.maximumLateness());
    }

    @Bean
    ScheduleRecovery scheduleRecovery(ScheduleJobs jobs, SchedulingProperties properties) {
        return new ScheduleRecovery(jobs, properties.enabled());
    }

    /** The schedule tools (`schedule_execution`, `schedule_message` and their management calls). */
    @Bean
    @Order(30)
    ToolProvider schedulingToolProvider(ScheduleService schedules) {
        return registry -> SchedulingTools.register(registry, schedules);
    }

    @Bean
    @Order(500)
    ContextContributor schedulingGuidance(ToolRegistry tools) {
        return ToolGuidanceContributor.whenRegistered(
                tools,
                SystemPrompts.SCHEDULING_INSTRUCTIONS,
                "schedule_execution",
                "schedule_message");
    }

    /**
     * Installs the trusted verifier of a scheduler-admitted execution's saved schedule association. Lazy: the
     * schedule service itself needs the gateway.
     */
    @Bean
    @Order(500)
    GatewayCustomizer scheduledExecutionValidator(ObjectProvider<ScheduleService> schedules) {
        return gateway -> gateway
                .setScheduledValidator(snapshot -> schedules.getObject().verifyExecution(snapshot));
    }
}
