package net.velocity.agent.history;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;

import net.velocity.agent.ToolRegistry;
import net.velocity.agent.persistence.ExecutionRepository;
import net.velocity.agent.spi.ContextContributor;
import net.velocity.agent.spi.GatewayCustomizer;
import net.velocity.agent.spi.ToolGuidanceContributor;
import net.velocity.agent.spi.ToolProvider;
import net.velocity.inference.SystemPrompts;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HistoryProperties.class)
public class HistoryConfiguration {

    @Bean
    @Lazy
    HistoryProjection historyProjection(MongoTemplate mongo) {
        return new MongoHistoryProjection(mongo);
    }

    @Bean
    @ConditionalOnMissingBean
    TaskHistoryService taskHistoryService(
            ExecutionRepository repository,
            HistoryProperties properties,
            @Lazy HistoryProjection projection) {
        return new TaskHistoryService(repository, properties, projection);
    }

    @Bean(destroyMethod = "close")
    HistoryProjector historyProjector(TaskHistoryService history, HistoryProperties properties) {
        return new HistoryProjector(history, properties.searchEnabled());
    }

    /** The task-history tools (`task_list`, `task_get` and the search calls). */
    @Bean
    @Order(40)
    ToolProvider taskHistoryToolProvider(TaskHistoryService history) {
        return registry -> TaskHistoryTools.register(registry, history);
    }

    @Bean
    @Order(800)
    ContextContributor taskHistoryGuidance(ToolRegistry tools) {
        return ToolGuidanceContributor
                .whenRegistered(tools, SystemPrompts.TASK_HISTORY_INSTRUCTIONS, "task_list", "task_get");
    }

    /** Projected history rows of a deleted execution go with it. */
    @Bean
    @Order(300)
    GatewayCustomizer historyDeletionListener(TaskHistoryService history) {
        return gateway -> gateway.registerDeletionListener(history::deleteExecution);
    }

    public static final class HistoryProjector implements AutoCloseable {

        private final java.util.concurrent.ScheduledExecutorService worker;

        HistoryProjector(TaskHistoryService history, boolean enabled) {
            worker = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform()
                            .daemon().name("history-projection").factory());
            if (enabled) {
                worker.scheduleWithFixedDelay(() -> {
                    try {
                        history.projectPending();
                    } catch (RuntimeException failure) {
                        org.slf4j.LoggerFactory.getLogger(HistoryProjector.class)
                                .warn("History projection remains pending");
                    }
                }, 10, 30, java.util.concurrent.TimeUnit.SECONDS);
            }
        }

        @Override
        public void close() {
            worker.shutdownNow();
        }
    }
}
