package net.velocity.usage;

import java.time.Instant;
import java.util.List;

import net.velocity.inference.objects.InferenceResponse.Usage;

/** No prompt, response content, credentials, or provider request body is retained. */
public record UsageRecord(
        String requestId,
        UsageLedger.Context context,
        Instant startedAt,
        String provider,
        String requestedModel,
        String model,
        boolean responseModelObserved,
        String pricingVersion,
        List<UsageProperties.Price> priceCatalog,
        Usage usage,
        boolean complete,
        long revision) {

    public UsageRecord {
        priceCatalog = List.copyOf(priceCatalog);
    }

    public UsageProperties.Price price() {
        if ("unknown".equals(model)) {
            return null;
        }
        return priceCatalog.stream()
                .filter(price -> price.provider().equals(provider) && price.model().equals(model))
                .findFirst().map(price -> price.forUsage(usage)).orElse(null);
    }

    public UsagePricing.Estimate estimate() {
        return UsagePricing.calculate(usage, price(), complete);
    }
}
