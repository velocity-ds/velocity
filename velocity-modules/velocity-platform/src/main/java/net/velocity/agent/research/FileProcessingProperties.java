package net.velocity.agent.research;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Preprocessing and work limits; model selection and credentials belong to the existing inference configuration. */
@ConfigurationProperties("agent.research.files")
public record FileProcessingProperties(
        String pdfinfoCommand,
        String renderCommand,
        String pdftotextCommand,
        int maxUnits,
        int pageDimension,
        int maxPageImageBytes,
        int maxOutputCharsPerUnit,
        Duration timeout) {

    public FileProcessingProperties {
        command(pdfinfoCommand);
        command(renderCommand);
        command(pdftotextCommand);
        minimum(maxUnits, 1, "max-units");
        minimum(pageDimension, 512, "page-dimension");
        minimum(maxPageImageBytes, 1024, "max-page-image-bytes");
        minimum(maxOutputCharsPerUnit, 1000, "max-output-chars-per-unit");
        if (timeout == null || timeout.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("File processing timeout must be at least one second");
        }
    }

    private static void command(String value) {
        if (value == null || value.isBlank() || value.length() > 4096
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "PDF executable must be a nonempty command name or path without control characters");
        }
    }

    private static void minimum(int value, int min, String name) {
        if (value < min) {
            throw new IllegalArgumentException("File processing " + name + " must be at least " + min);
        }
    }
}
