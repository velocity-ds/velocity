package net.velocity.inference;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/** Explicit request limits bound only for a selected default provider; output uses its max-tokens. */
@Validated
@ConfigurationProperties("inference.primary")
public record PrimaryInferenceProperties(
        @NotNull @PositiveOrZero Long contextCapacity,
        @NotNull @Positive Long inputAllowance,
        @NotNull @PositiveOrZero Long safetyMargin) {}
