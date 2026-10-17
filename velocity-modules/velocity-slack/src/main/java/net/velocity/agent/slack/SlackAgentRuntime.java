package net.velocity.agent.slack;

import java.util.concurrent.Executor;

import com.slack.api.methods.MethodsClient;

import net.velocity.agent.AgentGateway;
import net.velocity.agent.ContextHooks;
import net.velocity.agent.config.UploadProperties;
import net.velocity.agent.delivery.DeliveryJournal;
import net.velocity.agent.delivery.DeliveryStateReader;

import okhttp3.OkHttpClient;

/** Resources owned by the optional Slack adapter, separate from the shared agent worker runtime. */
public final class SlackAgentRuntime implements AutoCloseable, DeliveryStateReader {

    private final DeliveryService delivery;
    private final SlackSocketIngress socket;
    private final DeliveryJournal journal;

    public SlackAgentRuntime(
            AgentGateway gateway,
            ContextHooks hooks,
            DeliveryJournal journal,
            MethodsClient client,
            OkHttpClient http,
            Executor executor,
            String botName,
            String botToken,
            UploadProperties uploads,
            net.velocity.agent.research.ResearchService research,
            net.velocity.agent.accounts.AccountRepository accounts,
            java.time.Clock clock,
            net.velocity.usage.UsageReports usageReports) {
        this.journal = journal;
        SlackAccessVerifier access = new SlackApiAccessVerifier(client);
        delivery = new DeliveryService(
                gateway,
                journal,
                new SlackDeliveryTransport(client, access, botToken, research),
                botName,
                usageReports,
                clock);
        var ingress = new SlackIngressGateway(
                gateway,
                hooks,
                delivery,
                access,
                new SlackThreadHistory(client),
                new SlackFileDownloader(http, botToken, uploads),
                accounts,
                clock);
        socket = new SlackSocketIngress(ingress, client, executor);
    }

    public SlackSocketIngress socket() {
        return socket;
    }

    /** Contains private upload URLs: callers must project only safe delivery fields. */
    @Override
    public DeliveryJournal.State deliveryState(String executionId) {
        return journal.read(executionId);
    }

    public void recover() {
        delivery.recover();
    }

    @Override
    public void close() {
        delivery.close();
    }
}
