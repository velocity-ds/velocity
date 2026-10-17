package net.velocity.agent.research;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import net.velocity.agent.CancellationHandle;
import net.velocity.agent.io.HttpIO;
import net.velocity.agent.security.BrowserDestinationPolicy;
import net.velocity.agent.security.OperatorRoutes;
import net.velocity.agent.security.ReservedDestinations;
import net.velocity.inference.InferenceDeadlineScope;

/** Independent direct-fetch policy. Application DNS checks do not pin HttpClient's transport DNS. */
public final class ControlledDownloader implements AutoCloseable {

    public record Download(byte[] bytes, String mediaType, String filename, String safeUrl) {}

    private final ResearchProperties properties;
    private final BrowserDestinationPolicy policy;
    private final HttpClient client;
    private final HttpClient taskClient;
    private final Semaphore permits;
    private final LongSupplier nanoTime;

    public ControlledDownloader(
            ResearchProperties properties,
            OperatorRoutes routes,
            ReservedDestinations reserved) {
        this(properties, routes, reserved, System::nanoTime);
    }

    ControlledDownloader(
            ResearchProperties properties,
            OperatorRoutes routes,
            ReservedDestinations reserved,
            LongSupplier nanoTime) {
        this.properties = properties;
        this.nanoTime = Objects.requireNonNull(nanoTime);
        this.policy = new BrowserDestinationPolicy(
                properties.allowedHosts(),
                properties.privateOrigins(),
                routes,
                reserved);
        this.client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(properties.fetchTimeout()).build();
        this.taskClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        this.permits = new Semaphore(properties.maxConcurrentDownloads());
    }

    public Download fetch(String privateUrl, CancellationHandle cancellation) throws Exception {
        return fetch(privateUrl, cancellation, () -> true);
    }

    public Download fetch(
            String privateUrl,
            CancellationHandle cancellation,
            java.util.function.BooleanSupplier reserveRequest) throws Exception {
        if (!properties.directFetchEnabled()) {
            throw new ResearchService.ResearchException("blocked", "Direct document fetching is disabled");
        }
        Duration allowance = InferenceDeadlineScope.remainingOr(properties.fetchTimeout());
        long deadline = nanoTime.getAsLong() + allowance.toNanos();
        if (!permits.tryAcquire(allowance.toNanos(), TimeUnit.NANOSECONDS)) {
            throw new ResearchService.ResearchException(
                    "limit_reached",
                    "Download concurrency deadline reached");
        }
        try {
            String destination = privateUrl;
            for (int redirect = 0;; redirect++) {
                cancellation.check();
                policy.requireAllowed(destination);
                long remaining = deadline - nanoTime.getAsLong();
                if (remaining <= 0) {
                    throw new ResearchService.ResearchException("unavailable", "Download deadline reached");
                }
                if (!reserveRequest.getAsBoolean()) {
                    throw new ResearchService.ResearchException(
                            "limit_reached",
                            "Task download request limit reached");
                }
                // Durable admission may block on MongoDB. It consumes this same request deadline
                // and must not extend the subsequent network timeout or dispatch after cancellation.
                cancellation.check();
                remaining = deadline - nanoTime.getAsLong();
                if (remaining <= 0) {
                    throw new ResearchService.ResearchException("unavailable", "Download deadline reached");
                }
                HttpRequest request = HttpRequest.newBuilder(URI.create(destination)).GET()
                        .timeout(Duration.ofNanos(remaining))
                        .header(
                                "Accept",
                                "application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document,text/plain,text/html,*/*;q=0.1")
                        .header("Accept-Encoding", "identity").build();
                var activeClient = InferenceDeadlineScope.remaining().isPresent() ? taskClient : client;
                var pending = activeClient.sendAsync(request, info -> HttpIO.body(cancellation::check));
                // Keep the original deadline through response-body completion, including a stalled body.
                HttpResponse<byte[]> response = HttpIO.await(pending, cancellation, deadline, nanoTime);
                cancellation.check();
                if (response.statusCode() >= 300 && response.statusCode() <= 399) {
                    if (redirect >= properties.maxRedirects()) {
                        throw new ResearchService.ResearchException(
                                "blocked",
                                "Attachment redirect limit reached");
                    }
                    String location = response.headers().firstValue("location").orElseThrow(
                            () -> new ResearchService.ResearchException(
                                    "unavailable",
                                    "Redirect has no location"));
                    URI relative = URI.create(location), base = URI.create(destination);
                    // URI.resolve("?signature=...") incorrectly discards the last path component.
                    destination = location.startsWith("?")
                            ? base.getScheme() + "://" + base.getRawAuthority()
                                    + base.getRawPath() + location
                            : base.resolve(relative).toString();
                    continue;
                }
                if (response.statusCode() != 200) {
                    throw new ResearchService.ResearchException(
                            List.of(401, 403).contains(response.statusCode()) ? "blocked" : "unavailable",
                            "Attachment server returned HTTP " + response.statusCode());
                }
                String encoding = response.headers().firstValue("content-encoding").orElse("identity");
                if (!encoding.equalsIgnoreCase("identity")) {
                    throw new ResearchService.ResearchException(
                            "unsupported",
                            "Compressed HTTP content encoding is unsupported");
                }
                byte[] bytes = response.body();
                long length = response.headers().firstValueAsLong("content-length").orElse(bytes.length);
                if (length != bytes.length) {
                    throw new ResearchService.ResearchException(
                            "partial",
                            "Incomplete download was discarded");
                }
                String media = response.headers().firstValue("content-type")
                        .orElse("application/octet-stream").split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
                String start = new String(bytes, 0, Math.min(bytes.length, 65536), StandardCharsets.UTF_8);
                if (DocumentExtractor.html(start) && (DocumentExtractor.accessPage(start)
                        || (!media.equals("text/html") && !media.equals("application/xhtml+xml")))) {
                    throw new ResearchService.ResearchException(
                            "unavailable",
                            "Login/error or mislabeled HTML returned in place of the document");
                }
                String filename = URI.create(destination).getPath();
                filename = filename.substring(filename.lastIndexOf('/') + 1);
                String disposition = response.headers().firstValue("content-disposition").orElse("");
                if (!disposition.isBlank() && disposition.length() <= 4096) {
                    try {
                        String declared = org.springframework.http.ContentDisposition.parse(disposition)
                                .getFilename();
                        if (declared != null && !declared.isBlank()) {
                            filename = declared;
                        }
                    } catch (IllegalArgumentException malformed) {
                        /* Keep the safe URL-derived fallback. */ }
                }
                return new Download(
                        bytes,
                        media,
                        ResearchService.safeFilename(filename.isBlank() ? "document" : filename),
                        BrowserDestinationPolicy.safeUrl(destination));
            }
        } finally {
            permits.release();
        }
    }

    @Override
    public void close() {
        try {
            client.close();
        } finally {
            taskClient.close();
        }
    }
}
