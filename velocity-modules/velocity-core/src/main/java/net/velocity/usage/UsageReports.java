package net.velocity.usage;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import net.velocity.ApplicationTime;
import net.velocity.agent.state.ExecutionSnapshot;

/** All periods are inclusive calendar dates in the application zone, using the request's immutable start instant. */
public final class UsageReports {

    public static final List<String> EXCLUDED = List.of(
            "Usage before accounting was installed has no historical prices and is excluded from account totals.",
            "Playground and legacy requests without a trusted owner are recorded separately and excluded from account totals.");
    private final UsageRepository repository;
    private final Clock clock;
    private final UsageActivities activities;

    public UsageReports(UsageRepository repository, Clock clock, UsageActivities activities) {
        this.repository = repository;
        this.clock = clock;
        this.activities = Objects.requireNonNull(activities);
    }

    public record Rates(
            String version,
            String inputPerMillion,
            String outputPerMillion,
            String cacheReadPerMillion,
            String cacheCreationPerMillion,
            String cacheCreation5mPerMillion,
            String cacheCreation1hPerMillion) {

        static Rates of(String version, UsageProperties.Price price) {
            return price == null
                    ? null
                    : new Rates(
                            version,
                            amount(price.inputPerMillion()),
                            amount(price.outputPerMillion()),
                            amount(price.cacheReadPerMillion()),
                            amount(price.cacheCreationPerMillion()),
                            amount(price.cacheCreation5mPerMillion()),
                            amount(price.cacheCreation1hPerMillion()));
        }
    }

    public record Breakdown(
            String provider,
            String model,
            String activity,
            Rates rates,
            String estimatedUsd,
            String status,
            long requests,
            long inputTokens,
            long outputTokens,
            long totalTokens,
            long pricedRequests,
            long unpricedRequests,
            long incompleteRequests,
            long cacheReadTokens,
            long cacheCreationTokens,
            long cacheCreation5mTokens,
            long cacheCreation1hTokens,
            long reasoningTokens,
            List<String> unsupportedCategories) {}

    public record Report(
            String from,
            String to,
            String estimatedUsd,
            String status,
            long requests,
            long inputTokens,
            long outputTokens,
            long totalTokens,
            long pricedRequests,
            long unpricedRequests,
            long incompleteRequests,
            boolean historicalIncomplete,
            List<Breakdown> breakdown,
            List<String> excludedActivity) {}

    public record Summary(
            String currency,
            String timeZone,
            Report today,
            Report month,
            List<String> excludedActivity) {}

    public Summary summary(String owner) {
        var today = LocalDate.now(clock.withZone(ApplicationTime.zone()));
        var daily = range(owner, today, today, null, null, null);
        var monthly = range(owner, today.withDayOfMonth(1), today, null, null, null);
        return new Summary("USD", ApplicationTime.zone().getId(), daily, monthly, EXCLUDED);
    }

    public Report range(
            String owner,
            LocalDate from,
            LocalDate to,
            String provider,
            String model,
            String activity) {
        if (from == null || to == null || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= 366) {
            throw new IllegalArgumentException("Date range must include 1 to 366 days");
        }
        if (activity != null && !activities.contains(activity)) {
            throw new IllegalArgumentException("Unknown activity");
        }
        var filter = new UsageRepository.Filter(
                owner,
                from.atStartOfDay(ApplicationTime.zone()).toInstant(),
                to.plusDays(1).atStartOfDay(ApplicationTime.zone()).toInstant(),
                null,
                provider,
                model,
                activity);
        return combine(from.toString(), to.toString(), repository.aggregate(filter), false, false);
    }

    /** Count requests evidenced by task state, including sources predating complete ledger coverage. */
    public static long expectedTaskRequests(ExecutionSnapshot snapshot) {
        long recordedModels = snapshot.usageSources.keySet().stream()
                .filter(key -> key.startsWith("model:")).count();
        return Math.max(
                (long) snapshot.modelTurns + snapshot.delegatedModelRequests,
                snapshot.usageSources.size() + Math.max(0, snapshot.modelTurns - recordedModels));
    }

    public Report task(String owner, String id, long historicalTokens, long historicalRequests) {
        var rows = repository.aggregate(new UsageRepository.Filter(owner, null, null, id, null, null, null));
        long tokens = rows.stream().mapToLong(Breakdown::totalTokens).sum();
        long requests = rows.stream().mapToLong(Breakdown::requests).sum();
        boolean missing = tokens < historicalTokens || requests < historicalRequests;
        return combine(null, null, rows, missing, true);
    }

    public Report task(
            String owner,
            String id,
            long historicalTokens,
            long historicalRequests,
            Set<String> expectedSources,
            long rootModelTurns) {
        var report = task(owner, id, historicalTokens, historicalRequests);
        var sources = repository.sourceRequestKeys(owner, id);
        long rootModels = sources.stream().filter(
                key -> key.matches("model:[1-9][0-9]{0,9}")
                        && Long.parseLong(key.substring("model:".length())) <= rootModelTurns)
                .count();
        boolean historical = report.historicalIncomplete() || !sources.containsAll(expectedSources)
                || rootModels < rootModelTurns || sources.size() < historicalRequests;
        return historical == report.historicalIncomplete()
                ? report
                : combine(null, null, report.breakdown(), true, true);
    }

    public static Report combine(
            String from,
            String to,
            List<Breakdown> rows,
            boolean historical,
            boolean task) {
        long requests = 0, input = 0, output = 0, total = 0, priced = 0, unpriced = 0, incomplete = 0;
        BigDecimal usd = BigDecimal.ZERO;
        boolean known = false, partial = historical;
        for (var row : rows) {
            requests = Math.addExact(requests, row.requests());
            input = Math.addExact(input, row.inputTokens());
            output = Math.addExact(output, row.outputTokens());
            total = Math.addExact(total, row.totalTokens());
            priced = Math.addExact(priced, row.pricedRequests());
            unpriced = Math.addExact(unpriced, row.unpricedRequests());
            incomplete = Math.addExact(incomplete, row.incompleteRequests());
            if (row.estimatedUsd() != null) {
                usd = usd.add(new BigDecimal(row.estimatedUsd()));
                known = true;
            }
            partial |= !"complete".equals(row.status());
        }
        String state = requests == 0
                ? (task ? "unavailable" : "complete")
                : !known ? (unpriced > 0 ? "unpriced" : "unavailable") : partial ? "partial" : "complete";
        String estimate = requests == 0 ? (task ? null : "0") : known ? amount(usd) : null;
        return new Report(
                from,
                to,
                estimate,
                state,
                requests,
                input,
                output,
                total,
                priced,
                unpriced,
                incomplete,
                historical || task && requests == 0,
                List.copyOf(rows),
                task ? List.of() : EXCLUDED);
    }

    static String amount(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }
}
