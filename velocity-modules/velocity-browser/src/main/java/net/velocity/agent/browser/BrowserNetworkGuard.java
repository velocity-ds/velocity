package net.velocity.agent.browser;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpServer;

import net.velocity.agent.secrets.CredentialService;
import net.velocity.agent.security.BrowserDestinationPolicy;
import net.velocity.agent.security.BrowserRejectedException;

/**
 * Request policy only; Browser Use owns page observations and actions. This loopback endpoint is the only
 * place the Node hook consults, so every browser request is judged by {@link BrowserDestinationPolicy}.
 */
public final class BrowserNetworkGuard implements AutoCloseable {

    private static final JsonMapper JSON = new JsonMapper();
    private final BrowserDestinationPolicy destinations;
    private final HttpServer server;
    private final java.util.concurrent.ExecutorService workers;
    private final String token = UUID.randomUUID().toString() + UUID.randomUUID();
    private final BrowserContentRedactor secrets = new BrowserContentRedactor();
    private CredentialService.Metadata credential;
    private Runnable authority = () -> {};
    private Runnable submissionAuthority;
    private boolean submission;
    private boolean submissionNavigationRejected;
    private boolean closed;

    public BrowserNetworkGuard(BrowserDestinationPolicy destinations) {
        this.destinations = destinations;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 8);
        } catch (Exception failure) {
            throw new IllegalStateException("Browser network policy is unavailable");
        }
        workers = Executors.newFixedThreadPool(
                2,
                Thread.ofPlatform().daemon().name("velocity-browser-policy-", 0).factory());
        server.setExecutor(workers);
        server.createContext("/request", exchange -> {
            boolean allowed = false;
            byte[] body = null;
            try {
                String supplied = exchange.getRequestHeaders().getFirst("Authorization");
                if (!"POST".equals(exchange.getRequestMethod())
                        || !exchange.getRequestURI().getPath().equals("/request")
                        || supplied == null
                        || !MessageDigest.isEqual(
                                ("Bearer " + token).getBytes(StandardCharsets.UTF_8),
                                supplied.getBytes(StandardCharsets.UTF_8))) {
                    exchange.sendResponseHeaders(403, -1);
                    return;
                }
                body = exchange.getRequestBody().readAllBytes();
                @SuppressWarnings("unchecked")
                var request = JSON.readValue(body, Map.class);
                allowed = allow(request);
            } catch (Exception rejected) {
                /* No request, credential, or remote exception text is logged. */ } finally {
                if (body != null) {
                    java.util.Arrays.fill(body, (byte) 0);
                }
            }
            byte[] answer = (allowed
                    ? "{\"allow\":true}"
                    : "{\"allow\":false}").getBytes(StandardCharsets.UTF_8);
            try {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, answer.length);
                exchange.getResponseBody().write(answer);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    public Map<String, String> environment() {
        return Map.of(
                "VELOCITY_BROWSER_GUARD_URL",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/request",
                "VELOCITY_BROWSER_GUARD_TOKEN",
                token);
    }

    public synchronized void useCredential(
            CredentialService.Metadata metadata,
            Runnable check,
            String username,
            String password) {
        if (closed) {
            throw new IllegalStateException("Browser policy is closed");
        }
        check.run();
        if (credential != null && (!credential.credentialId().equals(metadata.credentialId())
                || credential.revision() != metadata.revision())) {
            throw new BrowserRejectedException(
                    "credential_attempt_stopped",
                    "Close this browser before choosing another saved credential.");
        }
        secrets.rememberRequired(username);
        secrets.rememberRequired(password);
        credential = metadata;
        authority = check;
    }

    public synchronized boolean credentialMatches(String id, long revision) {
        return credential != null && credential.credentialId().equals(id)
                && credential.revision() == revision;
    }

    public synchronized void beginSubmission(Runnable check) {
        authority.run();
        if (credential == null || submission) {
            throw new BrowserRejectedException(
                    "credential_attempt_stopped",
                    "A saved credential must be filled before submitting.");
        }
        Objects.requireNonNull(check).run();
        submissionAuthority = check;
        submissionNavigationRejected = false;
        submission = true;
    }

    public synchronized void endSubmission() {
        submission = false;
        submissionAuthority = null;
    }

    public synchronized boolean submissionNavigationRejected() {
        return submissionNavigationRejected;
    }

    /** Each redirect/subresource is checked before continuing; this does not pin transport DNS. */
    synchronized boolean allow(Map<?, ?> request) {
        boolean allowed = allowRequest(request);
        if (!allowed && submission && Boolean.TRUE.equals(request.get("navigation"))) {
            submissionNavigationRejected = true;
        }
        return allowed;
    }

    private boolean allowRequest(Map<?, ?> request) {
        if (closed) {
            return false;
        }
        try {
            if (!request.keySet().stream().allMatch(
                    Set.of(
                            "url",
                            "method",
                            "body",
                            "bodyKnown",
                            "headers",
                            "navigation",
                            "websocket",
                            "resourceType")::contains)) {
                return false;
            }
            String url = (String) request.get("url");
            destinations.requireAllowed(url);
            if (credential == null) {
                return true;
            }
            (submission ? submissionAuthority : authority).run();
            if (Boolean.FALSE.equals(request.get("bodyKnown"))) {
                return false;
            }
            String origin = BrowserDestinationPolicy.origin(url);
            String headers = JSON.writeValueAsString(request.get("headers"));
            if (secrets.containsSecret(url)) {
                return false;
            }
            boolean carriesPair = secrets.containsSecret(headers)
                    || secrets.containsSecret((String) request.get("body"));
            // Ordinary API, resource and authentication traffic follows the destination policy.
            // Only transmission of the private pair requires an explicit saved receiver.
            return !carriesPair || credential.credentialOrigins().contains(origin);
        } catch (Exception rejected) {
            return false;
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            closed = true;
            secrets.clear();
            credential = null;
            authority = () -> {};
            submission = false;
            submissionAuthority = null;
        }
        server.stop(0);
        workers.shutdownNow();
    }
}
