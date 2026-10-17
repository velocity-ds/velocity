package net.velocity.agent.http;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.Objects;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import net.velocity.agent.config.UploadProperties;
import net.velocity.agent.security.OperatorPaths;
import net.velocity.agent.security.OperatorRoutes;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

/** Local operator authority is the peer socket and origin, never forwarding headers. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class LocalOperatorFilter extends OncePerRequestFilter {

    public static final int MAX_BODY_BYTES = 1_048_576;

    /** The only route whose body may carry requester-supplied files, so the only one with a larger ceiling. */
    static final String UPLOAD_ROUTE = "/api/agent/executions";

    private final OperatorRoutes routes;
    private final UploadProperties uploads;

    public LocalOperatorFilter(OperatorRoutes routes, UploadProperties uploads) {
        this.routes = Objects.requireNonNull(routes, "Operator routes");
        this.uploads = Objects.requireNonNull(uploads, "Upload limits");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path;
        try {
            path = normalizedPath(request);
        } catch (IllegalArgumentException invalidPath) {
            return false;
        }
        return !routes.protectedServletPath(path);
    }

    private static String normalizedPath(HttpServletRequest request) {
        String raw = request.getServletPath();
        if (raw == null || raw.isEmpty()) {
            raw = request.getRequestURI().substring(request.getContextPath().length());
        }
        return OperatorPaths.normalize(raw);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain)
            throws ServletException, IOException {
        boolean secrets;
        long maxBodyBytes = MAX_BODY_BYTES;
        try {
            String path = normalizedPath(request);
            secrets = routes.sensitive(path);
            if ("POST".equalsIgnoreCase(request.getMethod()) && UPLOAD_ROUTE.equals(path)) {
                maxBodyBytes = Math.max(MAX_BODY_BYTES, uploads.requestBodyBytes());
            }
        } catch (IllegalArgumentException invalidPath) {
            secrets = true;
        }
        // Set before transport rejection and MVC conversion, including malformed or empty JSON requests.
        if (secrets) {
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Pragma", "no-cache");
        }
        if (!authorizedBoundary(request)) {
            response.sendError(403, "The agent operator API requires a same-origin loopback connection");
            return;
        }
        if (request.getContentLengthLong() > maxBodyBytes) {
            response.sendError(413, "Request exceeds " + maxBodyBytes + " bytes");
            return;
        }
        // Read the actual bytes as well: Content-Length does not constrain chunked requests.
        byte[] body = request.getInputStream().readNBytes(Math.toIntExact(maxBodyBytes) + 1);
        if (body.length > maxBodyBytes) {
            if (secrets) {
                java.util.Arrays.fill(body, (byte) 0);
            }
            response.sendError(413, "Request exceeds " + maxBodyBytes + " bytes");
            return;
        }
        try {
            chain.doFilter(new BufferedRequest(request, body), response);
        } finally {
            if (secrets) {
                java.util.Arrays.fill(body, (byte) 0);
            }
        }
    }

    static boolean authorizedBoundary(HttpServletRequest request) {
        if (!numericLoopback(request.getRemoteAddr())) {
            return false;
        }
        var hosts = Collections.list(request.getHeaders("Host"));
        var origins = Collections.list(request.getHeaders("Origin"));
        if (hosts.size() != 1 || origins.size() > 1) {
            return false;
        }
        var fetchSites = Collections.list(request.getHeaders("Sec-Fetch-Site"));
        if (fetchSites.size() > 1
                || fetchSites.stream().anyMatch(value -> "cross-site".equalsIgnoreCase(value))) {
            return false;
        }
        try {
            String scheme = request.isSecure() ? "https" : "http";
            URI host = URI.create(scheme + "://" + hosts.getFirst());
            if (!authorityOnly(host) || !loopbackHost(host.getHost())) {
                return false;
            }
            if (!origins.isEmpty()) {
                URI origin = URI.create(origins.getFirst());
                if (!authorityOnly(origin)
                        || !("http".equals(origin.getScheme()) || "https".equals(origin.getScheme()))
                        || !scheme.equals(origin.getScheme())
                        || !host.getHost().equalsIgnoreCase(origin.getHost())
                        || port(host) != port(origin)) {
                    return false;
                }
            }
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean authorityOnly(URI uri) {
        return uri.getHost() != null && uri.getRawUserInfo() == null && uri.getRawQuery() == null
                && uri.getRawFragment() == null && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                && (uri.getPort() == -1 || uri.getPort() > 0 && uri.getPort() <= 65535);
    }

    private static int port(URI uri) {
        return uri.getPort() == -1 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort();
    }

    private static boolean loopbackHost(String host) {
        return "localhost".equals(host.toLowerCase(Locale.ROOT)) || numericLoopback(host);
    }

    private static boolean numericLoopback(String value) {
        if (value == null || value.indexOf('%') >= 0) {
            return false;
        }
        String address = value.startsWith("[") && value.endsWith("]")
                ? value.substring(1, value.length() - 1)
                : value;
        if (!(address.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+") || address.indexOf(':') >= 0)) {
            return false;
        }
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (Exception exception) {
            return false;
        }
    }

    private static final class BufferedRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private BufferedRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public ServletInputStream getInputStream() {
            var input = new ByteArrayInputStream(body);
            return new ServletInputStream() {

                @Override
                public int read() {
                    return input.read();
                }

                @Override
                public int read(byte[] bytes, int offset, int length) {
                    return input.read(bytes, offset, length);
                }

                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("Synchronous bounded request");
                }
            };
        }
    }
}
