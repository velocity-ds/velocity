package net.velocity.agent.secrets;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/** Exact HTTPS origin policy. A suffix, wildcard, URL path or URL credential is never an origin. */
public final class CredentialOrigins {

    private CredentialOrigins() {
    }

    public static String canonical(String value) {
        try {
            URI uri = URI.create(value);
            if (value.length() > 512 || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))
                    || uri.getPort() == 0 || uri.getPort() < -1 || uri.getPort() > 65535
                    || uri.getHost().endsWith(".")) {
                throw new IllegalArgumentException();
            }
            return "https://" + uri.getHost().toLowerCase(Locale.ROOT)
                    + (uri.getPort() == -1 || uri.getPort() == 443 ? "" : ":" + uri.getPort());
        } catch (RuntimeException invalid) {
            throw new CredentialException("invalid_origin");
        }
    }

    public static List<String> policy(List<String> origins, boolean required) {
        if (origins == null || origins.size() > 16 || required && origins.isEmpty()) {
            throw new CredentialException("invalid_origin");
        }
        return origins.stream().map(CredentialOrigins::canonical).distinct().sorted().toList();
    }

    /** Exact callback paths; OAuth query state belongs to the website, never to saved metadata. */
    public static String callback(String value) {
        try {
            URI uri = URI.create(value);
            if (value.length() > 2048 || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getRawUserInfo() != null || uri.getHost() == null
                    || uri.getRawPath().contains("\\")
                    || !uri.normalize().getRawPath().equals(uri.getRawPath())) {
                throw new IllegalArgumentException();
            }
            String origin = canonical(uri.getScheme() + "://" + uri.getRawAuthority());
            String path = uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            // Avoid encoded path separators/dot segments whose routing interpretation varies by server.
            if (path.toLowerCase(Locale.ROOT).matches(".*%(2e|2f|5c).*")) {
                throw new IllegalArgumentException();
            }
            return origin + path;
        } catch (RuntimeException invalid) {
            throw new CredentialException("invalid_callback");
        }
    }

    public static List<String> callbacks(List<String> destinations) {
        if (destinations == null || destinations.size() > 16) {
            throw new CredentialException("invalid_callback");
        }
        return destinations.stream().map(CredentialOrigins::callback).distinct().sorted().toList();
    }
}
