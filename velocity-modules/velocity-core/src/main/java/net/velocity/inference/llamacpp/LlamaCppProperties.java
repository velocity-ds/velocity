package net.velocity.inference.llamacpp;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** Settings bound and validated only when llama.cpp is selected. */
@Validated
@ConfigurationProperties("inference.llama-cpp")
public record LlamaCppProperties(
        @NotBlank String baseUrl,
        String apiKey,
        @NotBlank String model,
        @Positive long maxTokens) {

    @AssertTrue(message = "base URL must be an absolute HTTP(S) URL with a host")
    public boolean isValidBaseUrl() {
        if (baseUrl == null || baseUrl.isBlank()) {
            return true; // @NotBlank reports missing values.
        }
        try {
            URI uri = URI.create(baseUrl);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    @Override
    public String toString() {
        return "LlamaCppProperties[baseUrl=" + baseUrl + ", apiKey=<redacted>, model=" + model
                + ", maxTokens=" + maxTokens + "]";
    }
}
