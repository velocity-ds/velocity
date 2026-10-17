package net.velocity.agent.slack;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;

import net.velocity.agent.state.ExecutionSnapshot;
import net.velocity.usage.UsageReports;

/** Shared Slack completion footer from recorded task usage and trusted account costs. */
public final class DeliveryUsageFooter {

    private static final Logger log = LoggerFactory.getLogger(DeliveryUsageFooter.class);
    private static final Gson JSON = new Gson();
    private final UsageReports usageReports;

    public DeliveryUsageFooter(UsageReports usageReports) {
        this.usageReports = usageReports;
    }

    /** Call only after worker settlement, then freeze the returned blocks before remote delivery. */
    public String render(ExecutionSnapshot snapshot) {
        String text = "Response complete " + snapshot.totalTokens + " (" + snapshot.inputTokens + " input · "
                + snapshot.outputTokens + " output) · " + estimatedCost(snapshot);
        if (snapshot.usageMissing) {
            text += " Usage incomplete: some provider usage is unavailable; totals include recorded usage only.";
        }
        return JSON.toJson(
                List.of(
                        Map.of(
                                "type",
                                "context",
                                "elements",
                                List.of(Map.of("type", "mrkdwn", "text", text)))));
    }

    private String estimatedCost(ExecutionSnapshot snapshot) {
        if (usageReports == null) {
            return "Estimated cost: unavailable";
        }
        try {
            var report = usageReports.task(
                    snapshot.scope.requireOwnerAccountId(),
                    snapshot.executionId,
                    snapshot.totalTokens,
                    UsageReports.expectedTaskRequests(snapshot),
                    snapshot.usageSources.keySet(),
                    snapshot.modelTurns);
            if (report == null || report.estimatedUsd() == null) {
                return report != null && "unpriced".equals(report.status())
                        ? "Estimated cost: unpriced"
                        : "Estimated cost: unavailable";
            }
            var amount = new java.math.BigDecimal(report.estimatedUsd()).stripTrailingZeros();
            String dollars = amount.setScale(Math.max(2, amount.scale())).toPlainString();
            boolean partial = !"complete".equals(report.status()) || report.historicalIncomplete();
            return "Estimated cost: $" + dollars + " USD"
                    + (partial ? " (partial; recorded usage only)" : "");
        } catch (RuntimeException unavailable) {
            log.warn(
                    "executionId={} stage=delivery_usage outcome=unavailable exception={}",
                    snapshot.executionId,
                    unavailable.getClass().getSimpleName());
            return "Estimated cost: unavailable";
        }
    }
}
