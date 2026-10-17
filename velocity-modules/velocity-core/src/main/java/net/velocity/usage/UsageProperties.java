package net.velocity.usage;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import net.velocity.inference.objects.InferenceResponse.Usage;

/** Operator-managed prices are independent of inference selection and admission limits. */
@ConfigurationProperties("usage")
public record UsageProperties(String pricingVersion, List<Price> prices) {

    public UsageProperties {
        if (pricingVersion == null || pricingVersion.isBlank() || pricingVersion.length() > 100) {
            throw new IllegalArgumentException("A bounded pricing version is required");
        }
        prices = prices == null ? List.of() : List.copyOf(prices);
        if (prices.size() > 256) {
            throw new IllegalArgumentException("At most 256 prices are supported");
        }
        var keys = new HashSet<String>();
        for (var price : prices) {
            if (!keys.add(price.provider() + "\n" + price.model())) {
                throw new IllegalArgumentException("Duplicate provider/model price");
            }
        }
    }

    public record Price(
            String provider,
            String model,
            BigDecimal inputPerMillion,
            BigDecimal outputPerMillion,
            BigDecimal cacheReadPerMillion,
            BigDecimal cacheCreationPerMillion,
            BigDecimal cacheCreation5mPerMillion,
            BigDecimal cacheCreation1hPerMillion,
            LongContext longContext) {

        public Price(
                String provider,
                String model,
                BigDecimal inputPerMillion,
                BigDecimal outputPerMillion,
                BigDecimal cacheReadPerMillion,
                BigDecimal cacheCreationPerMillion,
                BigDecimal cacheCreation5mPerMillion,
                BigDecimal cacheCreation1hPerMillion) {
            this(
                    provider,
                    model,
                    inputPerMillion,
                    outputPerMillion,
                    cacheReadPerMillion,
                    cacheCreationPerMillion,
                    cacheCreation5mPerMillion,
                    cacheCreation1hPerMillion,
                    null);
        }

        @ConstructorBinding
        public Price {
            if (!List.of("openai", "claude", "llama-cpp").contains(provider)
                    || model == null || model.isBlank() || model.length() > 256) {
                throw new IllegalArgumentException("Explicit provider and model are required for pricing");
            }
            if (inputPerMillion == null || outputPerMillion == null) {
                throw new IllegalArgumentException("Input and output prices are required");
            }
            decimal(inputPerMillion);
            decimal(outputPerMillion);
            decimal(cacheReadPerMillion);
            decimal(cacheCreationPerMillion);
            decimal(cacheCreation5mPerMillion);
            decimal(cacheCreation1hPerMillion);
            if (longContext != null) {
                for (var rate : new BigDecimal[]{inputPerMillion, cacheReadPerMillion,
                        cacheCreationPerMillion,
                        cacheCreation5mPerMillion, cacheCreation1hPerMillion}) {
                    decimal(multiplied(rate, longContext.inputMultiplier()));
                }
                decimal(multiplied(outputPerMillion, longContext.outputMultiplier()));
            }
        }

        /** The rule applies to the whole request, including cache subsets, strictly above its input threshold. */
        public Price forUsage(Usage usage) {
            if (longContext == null) {
                return this;
            }
            boolean applies = usage != null && usage.inputTokens() > longContext.inputTokenThreshold();
            var inputMultiplier = applies ? longContext.inputMultiplier() : BigDecimal.ONE;
            var outputMultiplier = applies ? longContext.outputMultiplier() : BigDecimal.ONE;
            // Effective snapshots omit the rule so a second pricing pass cannot multiply again.
            return new Price(
                    provider,
                    model,
                    multiplied(inputPerMillion, inputMultiplier),
                    multiplied(outputPerMillion, outputMultiplier),
                    multiplied(cacheReadPerMillion, inputMultiplier),
                    multiplied(cacheCreationPerMillion, inputMultiplier),
                    multiplied(cacheCreation5mPerMillion, inputMultiplier),
                    multiplied(cacheCreation1hPerMillion, inputMultiplier));
        }
    }

    public record LongContext(
            long inputTokenThreshold,
            BigDecimal inputMultiplier,
            BigDecimal outputMultiplier) {

        public LongContext {
            if (inputTokenThreshold <= 0) {
                throw new IllegalArgumentException("Long-context input threshold must be positive");
            }
            if (inputMultiplier == null || outputMultiplier == null || inputMultiplier.signum() <= 0
                    || outputMultiplier.signum() <= 0) {
                throw new IllegalArgumentException(
                        "Long-context multipliers must be positive bounded decimals");
            }
            decimal(inputMultiplier);
            decimal(outputMultiplier);
        }
    }

    private static BigDecimal multiplied(BigDecimal value, BigDecimal multiplier) {
        return value == null ? null : value.multiply(multiplier).stripTrailingZeros();
    }

    private static void decimal(BigDecimal value) {
        if (value != null && (value.signum() < 0 || value.precision() > 15 || value.scale() > 12
                || value.compareTo(new BigDecimal("1000000000")) > 0)) {
            throw new IllegalArgumentException(
                    "USD values must be nonnegative bounded decimals (up to 12 places)");
        }
    }
}
