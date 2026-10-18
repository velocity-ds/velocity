package net.velocity.usage;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.velocity.inference.objects.InferenceResponse.Usage;

import static org.assertj.core.api.Assertions.assertThat;

class UsagePricingTest {

    private static final UsageProperties.Price PRICE = new UsageProperties.Price(
            "claude",
            "model-x",
            new BigDecimal("3"),
            new BigDecimal("15"),
            new BigDecimal("0.3"),
            new BigDecimal("3.75"),
            null,
            null);

    @Test
    void completeUsageAtKnownRatesIsPricedExactly() {
        var estimate = UsagePricing.calculate(new Usage(1_000_000, 100_000), PRICE, true);
        assertThat(estimate.status()).isEqualTo("complete");
        assertThat(estimate.estimatedUsd()).isEqualByComparingTo("4.5");
    }

    @Test
    void cacheReadsArePricedSeparatelyFromRegularInput() {
        var usage = new Usage(1_000_000, 100_000, 1_100_000, 200_000, 0, 0, 0, 0, false, List.of());
        var estimate = UsagePricing.calculate(usage, PRICE, true);
        assertThat(estimate.status()).isEqualTo("complete");
        assertThat(estimate.estimatedUsd()).isEqualByComparingTo("3.96");
    }

    @Test
    void missingPriceOrUsageProducesNoEstimate() {
        assertThat(UsagePricing.calculate(new Usage(1, 1), null, true))
                .isEqualTo(new UsagePricing.Estimate(null, "unpriced"));
        assertThat(UsagePricing.calculate(null, PRICE, true))
                .isEqualTo(new UsagePricing.Estimate(null, "unavailable"));
    }

    @Test
    void partialEstimatesNameTheReason() {
        var cacheReadUnpriced = new UsageProperties.Price(
                "claude",
                "model-x",
                new BigDecimal("3"),
                new BigDecimal("15"),
                null,
                null,
                null,
                null);
        var usage = new Usage(1_000_000, 100_000, 1_100_000, 200_000, 0, 0, 0, 0, false, List.of());
        var missingRate = UsagePricing.calculate(usage, cacheReadUnpriced, true);
        assertThat(missingRate.status()).isEqualTo("partial");
        assertThat(missingRate.estimatedUsd()).isEqualByComparingTo("3.9");

        assertThat(UsagePricing.calculate(new Usage(1_000_000, 100_000), PRICE, false).status())
                .isEqualTo("partial");
        assertThat(UsagePricing.calculate(new Usage(1_000_000, 100_000, 1_200_000), PRICE, true).status())
                .isEqualTo("partial");
        var unsupported = new Usage(1_000_000, 100_000, 1_100_000, 0, 0, 0, 0, 0, false, List.of("audio"));
        assertThat(UsagePricing.calculate(unsupported, PRICE, true).status()).isEqualTo("partial");
    }

    @Test
    void usageConsistingOnlyOfUnpricedCategoriesIsUnpriced() {
        var onlyCacheReads = new Usage(100, 0, 100, 100, 0, 0, 0, 0, false, List.of());
        var cacheReadUnpriced = new UsageProperties.Price(
                "claude",
                "model-x",
                new BigDecimal("3"),
                new BigDecimal("15"),
                null,
                null,
                null,
                null);
        assertThat(UsagePricing.calculate(onlyCacheReads, cacheReadUnpriced, true).status())
                .isEqualTo("unpriced");
    }

    @Test
    void longContextMultipliersApplyAboveTheThresholdOnly() {
        var longContext = new UsageProperties.Price(
                "claude",
                "model-x",
                new BigDecimal("3"),
                new BigDecimal("15"),
                null,
                null,
                null,
                null,
                new UsageProperties.LongContext(200_000, new BigDecimal("2"), new BigDecimal("1.5")));
        assertThat(UsagePricing.calculate(new Usage(200_000, 100_000), longContext, true).estimatedUsd())
                .isEqualByComparingTo("2.1");
        assertThat(UsagePricing.calculate(new Usage(200_001, 100_000), longContext, true).estimatedUsd())
                .isEqualByComparingTo("3.450006");
    }
}
