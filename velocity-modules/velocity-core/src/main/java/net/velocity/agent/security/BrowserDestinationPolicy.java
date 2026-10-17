package net.velocity.agent.security;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Application-level request filtering; use network egress controls against DNS rebinding and browser escape.
 *
 * <p>This class is the single owner of destination rules (scheme, host syntax, allowlist, private origins,
 * address classification). {@code BrowserNetworkGuard} exposes it to the Node network hook, which only forwards
 * requests and classifies nothing. The Python bridge keeps a defense-in-depth copy of the URL shape checks in
 * {@code runtime/browser-use-runtime/server.py}; it must never accept what this class rejects.
 */
public final class BrowserDestinationPolicy {

    private final Set<String> allowedHosts;
    private final Set<String> privateOrigins;
    private final DnsResolver resolver;
    private final OperatorRoutes routes;
    private final ReservedDestinations reserved;

    /**
     * Shared by browser navigation and direct document fetching; it needs no browser process configuration.
     * {@code routes} is the same bean that protects the inbound operator API, so a browser can never be pointed at
     * an operator route on a private origin, whichever features are loaded. {@code reserved} holds the destinations
     * that loaded features keep for their own server-owned integrations.
     */
    public BrowserDestinationPolicy(
            List<String> allowedHosts,
            List<String> privateOrigins,
            OperatorRoutes routes,
            ReservedDestinations reserved) {
        this(allowedHosts, privateOrigins, routes, reserved, InetAddress::getAllByName);
    }

    private BrowserDestinationPolicy(
            List<String> allowedHosts,
            List<String> privateOrigins,
            OperatorRoutes routes,
            ReservedDestinations reserved,
            DnsResolver resolver) {
        this.allowedHosts = Set.copyOf(allowedHosts);
        this.privateOrigins = Set.copyOf(privateOrigins);
        this.routes = Objects.requireNonNull(routes, "Operator routes");
        this.reserved = Objects.requireNonNull(reserved, "Reserved destinations");
        this.resolver = resolver;
    }

    /** Call before each navigation and every intercepted request, including redirects and subresources. */
    public void requireAllowed(String url) {
        Destination destination = parse(url);
        if (reserved.browserHostBlocked(destination.host())) {
            throw denied(
                    "The destination is reserved for a server-owned integration and is unavailable to the browser.");
        }
        if (!allowedHosts.isEmpty() && !allowedHosts.contains(destination.host())) {
            throw denied("The destination host is outside the configured browser allowlist.");
        }
        if (privateOrigins.contains(destination.origin())) {
            if (operatorPath(destination.uri())) {
                throw denied("Local operator API routes are unavailable to browser tools.");
            }
            return;
        }
        String host = destination.host();
        if (isInternalName(host)) {
            throw denied("Private and local browser destinations require an explicit origin override.");
        }
        try {
            InetAddress[] addresses = resolver.resolve(host);
            if (addresses == null || addresses.length == 0) {
                throw new UnknownHostException();
            }
            for (InetAddress address : addresses) {
                if (address == null || !isPublic(address)) {
                    throw denied("Private, local, reserved, and metadata browser destinations are blocked.");
                }
            }
        } catch (UnknownHostException exception) {
            throw denied("The browser destination could not be resolved safely.");
        }
    }

    private boolean operatorPath(URI uri) {
        return routes.protectedBrowserPath(OperatorPaths.normalize(uri.getRawPath()));
    }

    /** Canonical origin, excluding the default port. Never includes path, query, or credentials. */
    public static String origin(String url) {
        return parse(url).origin();
    }

    /** Exact observed source identity: origin, normalized path and query; fragments do not select a source. */
    public static boolean sameSource(String expectedUrl, String observedUrl) {
        try {
            URI expected = URI.create(expectedUrl.strip()).normalize(),
                    actual = URI.create(observedUrl.strip()).normalize();
            String expectedPath = expected.getRawPath(), actualPath = actual.getRawPath();
            if (expectedPath == null || expectedPath.isEmpty()) {
                expectedPath = "/";
            }
            if (actualPath == null || actualPath.isEmpty()) {
                actualPath = "/";
            }
            return origin(expected.toString()).equals(origin(actual.toString()))
                    && expectedPath.equals(actualPath)
                    && Objects.equals(expected.getRawQuery(), actual.getRawQuery())
                    && expected.getRawUserInfo() == null && actual.getRawUserInfo() == null;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    /** Ordinary observations omit query strings and fragments, which often contain credentials. */
    public static String safeUrl(String url) {
        try {
            Destination destination = parse(url);
            String path = destination.uri().getRawPath();
            String safe = destination.origin() + (path == null || path.isEmpty() ? "/" : path);
            return safe.length() > 2048 ? safe.substring(0, 2048) + "…" : safe;
        } catch (BrowserRejectedException exception) {
            return "[URL withheld]";
        }
    }

    public static String configuredHost(String value) {
        try {
            String authority = value != null && value.contains(":") && !value.startsWith("[")
                    ? "[" + value + "]"
                    : value;
            Destination destination = parse("https://" + authority + "/");
            if (destination.uri().getPort() != -1 || !destination.uri().getRawPath().equals("/")
                    || destination.uri().getRawQuery() != null
                    || destination.uri().getRawFragment() != null) {
                throw denied("Host expected.");
            }
            return destination.host();
        } catch (BrowserRejectedException exception) {
            throw new IllegalArgumentException(
                    "Browser allowed-hosts must contain exact host names or IP literals, without ports or wildcards");
        }
    }

    public static String configuredOrigin(String value) {
        try {
            Destination destination = parse(value);
            URI uri = destination.uri();
            if (uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty()
                            && !uri.getRawPath().equals("/"))) {
                throw denied("Origin expected.");
            }
            return destination.origin();
        } catch (BrowserRejectedException exception) {
            throw new IllegalArgumentException(
                    "Browser private-origins must contain exact HTTP(S) origins without paths, queries, fragments, or credentials");
        }
    }

    private static Destination parse(String value) {
        if (value == null || value.isBlank() || value.indexOf('\\') >= 0
                || value.codePoints().anyMatch(
                        character -> Character.isWhitespace(character)
                                || Character.isISOControl(character))) {
            throw denied("A well-formed absolute HTTP(S) URL is required.");
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ((!scheme.equals("http") && !scheme.equals("https")) || uri.isOpaque()
                    || uri.getRawUserInfo() != null || uri.getHost() == null
                    || uri.getPort() == 0 || uri.getPort() > 65535 || uri.getRawAuthority().endsWith(":")) {
                throw denied("Only HTTP(S) destinations without URL credentials are permitted.");
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (host.startsWith("[") && host.endsWith("]")) {
                host = host.substring(1, host.length() - 1);
            }
            validateHost(host);
            int port = uri.getPort();
            String renderedHost = host.contains(":") ? "[" + host + "]" : host;
            String origin = scheme + "://" + renderedHost
                    + (port < 0 || port == (scheme.equals("https") ? 443 : 80) ? "" : ":" + port);
            return new Destination(uri, host, origin);
        } catch (URISyntaxException exception) {
            throw denied("A well-formed absolute HTTP(S) URL is required.");
        }
    }

    private static void validateHost(String host) {
        if (host.isEmpty() || host.length() > 253 || host.contains("%") || host.endsWith(".")) {
            throw denied("The browser destination host is invalid.");
        }
        if (host.contains(":")) {
            try {
                InetAddress.getByName(host);
            } catch (UnknownHostException exception) {
                throw denied("The browser destination host is invalid.");
            }
            return;
        }
        String[] labels = host.split("\\.", -1);
        boolean numeric = host.matches("[0-9.]+")
                || host.matches("(?i)(?:0x[0-9a-f]+|[0-9]+)(?:\\.(?:0x[0-9a-f]+|[0-9]+))*");
        String lastLabel = labels[labels.length - 1];
        if (!numeric && (lastLabel.matches("[0-9]+") || lastLabel.matches("0x[0-9a-f]*"))) {
            throw denied("Ambiguous numeric-suffix browser hosts are forbidden.");
        }
        if (numeric) {
            if (labels.length != 4) {
                throw denied("Noncanonical numeric browser hosts are forbidden.");
            }
            for (String label : labels) {
                if (!label.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(label) > 255) {
                    throw denied("Noncanonical numeric browser hosts are forbidden.");
                }
            }
        }
        for (String label : labels) {
            if (!label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) {
                throw denied("The browser destination host is invalid.");
            }
        }
    }

    private static boolean isInternalName(String host) {
        if (!host.contains(".") && !host.contains(":")) {
            return true;
        }
        return List.of("localhost", "local", "internal", "lan", "home", "test", "invalid")
                .stream().anyMatch(suffix -> host.equals(suffix) || host.endsWith("." + suffix));
    }

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int a = Byte.toUnsignedInt(bytes[0]), b = Byte.toUnsignedInt(bytes[1]),
                    c = Byte.toUnsignedInt(bytes[2]);
            return !(a == 0 || a == 10 || a == 127 || a >= 224
                    || (a == 100 && b >= 64 && b <= 127)
                    || (a == 169 && b == 254) || (a == 172 && b >= 16 && b <= 31)
                    || (a == 192 && b == 168) || (a == 192 && b == 0 && (c == 0 || c == 2))
                    || (a == 192 && b == 88 && c == 99)
                    || (a == 198 && (b == 18 || b == 19 || b == 51 && c == 100))
                    || (a == 203 && b == 0 && c == 113)
                    || address.getHostAddress().equals("168.63.129.16"));
        }
        // Global unicast only; reject ULA, local, multicast, NAT64, and transition ranges.
        if (bytes.length != 16 || (bytes[0] & 0xe0) != 0x20) {
            return false;
        }
        int b1 = Byte.toUnsignedInt(bytes[1]), b2 = Byte.toUnsignedInt(bytes[2]),
                b3 = Byte.toUnsignedInt(bytes[3]);
        return !(bytes[0] == 0x20 && b1 == 0x02 // 6to4 embeds an unchecked IPv4 destination.
                || bytes[0] == 0x20 && b1 == 0x01 && b2 < 2 // IETF special-purpose range.
                || bytes[0] == 0x20 && b1 == 0x01 && b2 == 0x0d && b3 == 0xb8 // Documentation.
                || bytes[0] == 0x3f && b1 == 0xfe // Retired 6bone / old Teredo stacks.
                || bytes[0] == 0x3f && b1 == 0xff && (b2 & 0xf0) == 0); // Documentation.
    }

    private static BrowserRejectedException denied(String message) {
        return new BrowserRejectedException("destination_denied", message);
    }

    @FunctionalInterface
    interface DnsResolver {

        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private record Destination(URI uri, String host, String origin) {}
}
