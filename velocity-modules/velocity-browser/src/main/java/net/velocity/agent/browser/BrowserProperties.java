package net.velocity.agent.browser;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import net.velocity.agent.security.BrowserDestinationPolicy;

/** Operator-owned local process configuration; none of these values are tool arguments. */
@ConfigurationProperties("agent.browser")
public record BrowserProperties(
        boolean enabled,
        boolean headless,
        String nodeExecutable,
        String pythonExecutable,
        String launcherScript,
        List<String> allowedHosts,
        List<String> privateOrigins) {

    public BrowserProperties {
        for (String value : new String[]{nodeExecutable, pythonExecutable, launcherScript}) {
            if (value == null || value.isBlank() || value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Browser executable and launcher paths are required");
            }
        }
        allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
        privateOrigins = privateOrigins == null ? List.of() : List.copyOf(privateOrigins);
        allowedHosts = allowedHosts.stream().map(BrowserDestinationPolicy::configuredHost).distinct()
                .toList();
        privateOrigins = privateOrigins.stream().map(BrowserDestinationPolicy::configuredOrigin).distinct()
                .toList();
    }
}
