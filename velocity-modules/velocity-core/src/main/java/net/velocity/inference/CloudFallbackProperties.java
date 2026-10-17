package net.velocity.inference;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/** Bound only after explicit enablement; no inactive hosted settings or credentials are required. */
@Validated
@ConfigurationProperties("inference.cloud-fallback")
public record CloudFallbackProperties(
        @NotBlank @Pattern(regexp = "openai|claude", message = "must select openai or claude") String provider,
        @NotBlank String model,
        @NotNull @PositiveOrZero Long contextCapacity,
        @NotNull @Positive Long inputAllowance,
        @NotNull @Positive Long outputReserve,
        @NotNull @PositiveOrZero Long safetyMargin) {

    @AssertTrue(message = "input allowance, output reserve, and safety margin must fit context capacity")
    public boolean isWithinContextCapacity() {
        // Missing values are reported by their field constraints without unboxing nulls.
        if (contextCapacity == null || inputAllowance == null || outputReserve == null
                || safetyMargin == null) {
            return true;
        }
        return InferenceCapacity.fits(contextCapacity, inputAllowance, outputReserve, safetyMargin);
    }
}
