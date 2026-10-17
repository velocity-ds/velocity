package net.velocity.agent.research;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import net.velocity.agent.security.BrowserDestinationPolicy;

@ConfigurationProperties("agent.research")
public record ResearchProperties(
        boolean enabled,
        boolean directFetchEnabled,
        List<String> allowedHosts,
        List<String> privateOrigins,
        int maxTaskRequests,
        Duration fetchTimeout,
        int maxRedirects,
        int maxConcurrentDownloads,
        int maxExtractedChars,
        int maxExpandedBytes,
        int maxArchiveEntries,
        Duration extractionTimeout,
        int maxArtifacts,
        int maxReportBytes,
        int maxOutputFileBytes) {

    public ResearchProperties {
        minimum(maxTaskRequests, 1, "max-task-requests");
        minimum(maxRedirects, 0, "max-redirects");
        minimum(maxConcurrentDownloads, 1, "max-concurrent-downloads");
        minimum(maxExtractedChars, 1000, "max-extracted-chars");
        minimum(maxExpandedBytes, 1024, "max-expanded-bytes");
        minimum(maxArchiveEntries, 1, "max-archive-entries");
        minimum(maxArtifacts, 1, "max-artifacts");
        minimum(maxReportBytes, 1024, "max-report-bytes");
        minimum(maxOutputFileBytes, 1024, "max-output-file-bytes");
        duration(fetchTimeout, "fetch-timeout");
        duration(extractionTimeout, "extraction-timeout");
        allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
        privateOrigins = privateOrigins == null ? List.of() : List.copyOf(privateOrigins);
        allowedHosts = allowedHosts.stream().map(BrowserDestinationPolicy::configuredHost).distinct()
                .toList();
        privateOrigins = privateOrigins.stream().map(BrowserDestinationPolicy::configuredOrigin).distinct()
                .toList();
    }

    private static void minimum(long value, long min, String name) {
        if (value < min) {
            throw new IllegalArgumentException("Research " + name + " must be at least " + min);
        }
    }

    private static void duration(Duration value, String name) {
        if (value == null || value.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("Research " + name + " must be at least 1 second");
        }
    }
}
