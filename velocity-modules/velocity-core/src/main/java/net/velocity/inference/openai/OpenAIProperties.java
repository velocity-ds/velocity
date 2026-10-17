package net.velocity.inference.openai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** Settings bound and validated only when OpenAI is selected. */
@Validated
@ConfigurationProperties("inference.openai")
public record OpenAIProperties(@NotBlank String model, @Positive long maxTokens) {}
