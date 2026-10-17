package net.velocity.slack;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.slack.api.Slack;
import com.slack.api.SlackConfig;
import com.slack.api.bolt.App;
import com.slack.api.bolt.AppConfig;
import com.slack.api.bolt.jakarta_socket_mode.SocketModeApp;
import com.slack.api.util.http.SlackHttpClient;

import net.velocity.agent.AgentGateway;
import net.velocity.agent.ContextHooks;
import net.velocity.agent.config.UploadProperties;
import net.velocity.agent.delivery.MongoDeliveryJournal;
import net.velocity.agent.scheduling.ScheduleDelivery;
import net.velocity.agent.slack.SlackAgentRuntime;
import net.velocity.agent.slack.SlackApiAccessVerifier;
import net.velocity.agent.slack.SlackDeliveryTransport;
import net.velocity.agent.slack.SlackScheduleDelivery;

/** Opt-in Socket Mode connection; no public HTTP endpoint or signing secret is needed. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "slack", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(SlackProperties.class)
public class SlackConfiguration {

    @Bean
    ScheduleDelivery slackScheduleDelivery(
            MongoTemplate mongo,
            Slack slackClient,
            SlackProperties properties,
            org.springframework.beans.factory.ObjectProvider<net.velocity.usage.UsageReports> usageReports) {
        var client = slackClient.methods(properties.botToken());
        var access = new SlackApiAccessVerifier(client);
        var journal = new MongoDeliveryJournal(mongo, MongoDeliveryJournal.SCHEDULE_COLLECTION);
        return new SlackScheduleDelivery(
                journal,
                new SlackDeliveryTransport(client, access, properties.botToken()),
                access,
                usageReports.getIfAvailable());
    }

    @Bean(destroyMethod = "close")
    SlackSdkExecutors slackSdkExecutors(SlackProperties properties) {
        // Require validated settings before allocating SDK resources.
        return new SlackSdkExecutors();
    }

    @Bean(destroyMethod = "close")
    Slack slackClient(SlackSdkExecutors executors) {
        var config = new SlackConfig();
        config.setExecutorServiceProvider(executors);
        config.setHttpClientReadTimeoutMillis(15_000);
        config.setHttpClientWriteTimeoutMillis(15_000);
        config.setHttpClientCallTimeoutMillis(20_000);
        // Stream appends cannot be replayed safely after a lost acknowledgement.
        var http = new SlackHttpClient(
                SlackHttpClient.buildOkHttpClient(config).newBuilder()
                        .connectTimeout(10, TimeUnit.SECONDS).retryOnConnectionFailure(false)
                        .followRedirects(false).followSslRedirects(false).build());
        http.setConfig(config);
        return Slack.getInstance(config, http);
    }

    @Bean
    @DependsOn("slackClient")
    ThreadPoolTaskExecutor slackMessageExecutor(SlackProperties properties) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.workers());
        executor.setMaxPoolSize(properties.workers());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix("slack-ingress-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }

    @Bean(destroyMethod = "stop")
    App slackApp(Slack slackClient, SlackProperties properties, SlackSdkExecutors executors) {
        // SlackAgentRuntime installs the acknowledging Socket Mode listeners.
        return new App(
                AppConfig.builder()
                        .slack(slackClient)
                        .executorServiceProvider(executors)
                        .singleTeamBotToken(properties.botToken())
                        .build());
    }

    @Bean(destroyMethod = "close", initMethod = "recover")
    SlackAgentRuntime slackAgentRuntime(
            AgentGateway gateway,
            ContextHooks hooks,
            MongoTemplate mongo,
            net.velocity.agent.accounts.AccountRepository accounts,
            java.time.Clock clock,
            Slack slackClient,
            SlackProperties properties,
            UploadProperties uploads,
            org.springframework.beans.factory.ObjectProvider<net.velocity.agent.research.ResearchService> research,
            org.springframework.beans.factory.ObjectProvider<net.velocity.usage.UsageReports> usageReports,
            @Qualifier("slackMessageExecutor") ThreadPoolTaskExecutor executor) {
        var journal = new MongoDeliveryJournal(mongo);
        // Attached-file downloads reuse the SDK's HTTP pools under the agent.uploads limits.
        return new SlackAgentRuntime(
                gateway,
                hooks,
                journal,
                slackClient.methods(properties.botToken()),
                slackClient.getHttpClient().getOkHttpClient(),
                executor,
                properties.botName(),
                properties.botToken(),
                uploads,
                research.getIfAvailable(),
                accounts,
                clock,
                usageReports.getIfAvailable());
    }

    @Bean
    SlackSocketReadiness slackSocketReadiness() {
        return new SlackSocketReadiness();
    }

    @Bean(destroyMethod = "")
    SocketModeApp slackSocketModeApp(
            SlackProperties properties,
            App slackApp,
            SlackAgentRuntime runtime,
            SlackSocketReadiness readiness) throws IOException {
        // SlackSocketConnection owns both this adapter and its underlying WebSocket client.
        return new SocketModeApp(() -> {
            try {
                var client = com.slack.api.jakarta_socket_mode.JakartaSocketModeClientFactory
                        .create(slackApp.slack(), properties.appToken());
                readiness.listenTo(client);
                runtime.socket().install(client);
                return client;
            } catch (IOException failure) {
                throw new java.io.UncheckedIOException("Could not initialize Slack Socket Mode", failure);
            }
        }, slackApp);
    }

    @Bean
    SlackSocketConnection slackSocketConnection(
            SocketModeApp slackSocketModeApp,
            SlackSocketReadiness readiness) {
        return new SlackSocketConnection(slackSocketModeApp, readiness);
    }
}
