package net.velocity.usage;

import java.math.BigDecimal;

import net.velocity.inference.objects.InferenceResponse.Usage;

/** Input includes cache subtotals, and output already includes reasoning. */
public final class UsagePricing {

    private UsagePricing() {
    }

    public record Estimate(BigDecimal estimatedUsd, String status) {}

    public static Estimate calculate(Usage usage, UsageProperties.Price price, boolean complete) {
        if (price == null) {
            return new Estimate(null, "unpriced");
        }
        if (usage == null) {
            return new Estimate(null, "unavailable");
        }
        price = price.forUsage(usage);
        if (usage.cacheReadTokens() > usage.inputTokens()
                || usage.cacheCreationTokens() > usage.inputTokens() - usage.cacheReadTokens()
                || usage.cacheCreation5mTokens() > usage.cacheCreationTokens()
                || usage.cacheCreation1hTokens() > usage.cacheCreationTokens() - usage.cacheCreation5mTokens()
                || usage.reasoningTokens() > usage.outputTokens()) {
            return new Estimate(null, "unavailable");
        }
        long regular = usage.inputTokens() - usage.cacheReadTokens() - usage.cacheCreationTokens();
        long creation = usage.cacheCreationTokens() - usage.cacheCreation5mTokens()
                - usage.cacheCreation1hTokens();
        var parts = new Part[]{new Part(regular, price.inputPerMillion()),
                new Part(usage.outputTokens(), price.outputPerMillion()),
                new Part(usage.cacheReadTokens(), price.cacheReadPerMillion()),
                new Part(creation, price.cacheCreationPerMillion()),
                new Part(usage.cacheCreation5mTokens(), price.cacheCreation5mPerMillion()),
                new Part(usage.cacheCreation1hTokens(), price.cacheCreation1hPerMillion())};
        BigDecimal total = BigDecimal.ZERO;
        boolean missingRate = false, knownTokens = false;
        for (var part : parts) {
            if (part.tokens == 0) {
                continue;
            }
            if (part.rate == null) {
                missingRate = true;
            } else {
                total = total.add(part.rate.multiply(BigDecimal.valueOf(part.tokens)).movePointLeft(6));
                knownTokens = true;
            }
        }
        if (missingRate && !knownTokens) {
            return new Estimate(null, "unpriced");
        }
        boolean partial = missingRate || !complete || usage.incomplete()
                || !usage.unsupportedCategories().isEmpty()
                || usage.totalTokens() != usage.inputTokens() + usage.outputTokens();
        if (partial && !knownTokens) {
            return new Estimate(null, "unavailable");
        }
        return new Estimate(total, partial ? "partial" : "complete");
    }

    private record Part(long tokens, BigDecimal rate) {}
}
