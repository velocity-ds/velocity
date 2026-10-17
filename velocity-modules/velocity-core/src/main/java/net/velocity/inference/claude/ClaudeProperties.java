package net.velocity.inference.claude;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * Settings bound and validated only when Claude is selected. {@code promptCaching} places cache breakpoints
 * on the system prompt, the tool definitions and the latest conversation turn so repeated prefixes of an agent
 * loop are read from the provider's prompt cache; see docs/inference.md.
 */
@Validated
@ConfigurationProperties("inference.claude")
public record ClaudeProperties(@NotBlank String model, @Positive long maxTokens, boolean promptCaching) {

    /** Binds the canonical constructor; the two-argument one below would otherwise make the choice ambiguous. */
    @ConstructorBinding
    public ClaudeProperties {
    }

    /** Caching on, as for the cloud continuation profile, which has no separate switch. */
    public ClaudeProperties(String model, long maxTokens) {
        this(model, maxTokens, true);
    }
}
