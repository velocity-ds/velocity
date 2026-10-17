package net.velocity.slack;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** Single-workspace credentials and limits, validated only when Slack is enabled. */
@Validated
@ConfigurationProperties("slack")
public record SlackProperties(
        @NotBlank String botToken,
        @NotBlank String appToken,
        @NotBlank String botName,
        @Positive int workers,
        @Positive int queueCapacity) {

    @Override
    public String toString() {
        return "SlackProperties[botToken=<redacted>, appToken=<redacted>, workers=" + workers
                + ", queueCapacity=" + queueCapacity + ", botName=" + botName + "]";
    }
}
