package net.velocity.agent.security;

import java.net.InetAddress;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import net.velocity.agent.spi.ReservedDestinationPolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Only destinations that resolve without DNS are used: IP literals, names the policy rejects by shape, and
 * configured private origins.
 */
class BrowserDestinationPolicyTest {

    private static final OperatorRoutes ROUTES = new OperatorRoutes(List.of());
    private static final ReservedDestinations NOTHING_RESERVED = new ReservedDestinations(List.of());

    @Test
    void originOmitsDefaultPortsPathsQueriesAndCase() {
        assertThat(BrowserDestinationPolicy.origin("HTTPS://Example.COM:443/path?q=1#f"))
                .isEqualTo("https://example.com");
        assertThat(BrowserDestinationPolicy.origin("http://example.com:80/")).isEqualTo("http://example.com");
        assertThat(BrowserDestinationPolicy.origin("http://example.com:8080/"))
                .isEqualTo("http://example.com:8080");
        assertThat(BrowserDestinationPolicy.origin("http://[::1]:80/x")).isEqualTo("http://[::1]");
        assertThat(BrowserDestinationPolicy.origin("https://93.184.216.34/"))
                .isEqualTo("https://93.184.216.34");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ftp://example.com/",
            "mailto:someone@example.com",
            "http://user:secret@example.com/",
            "http://example.com:0/",
            "http://example.com:70000/",
            "http://example.com:/",
            "http://exa mple.com/",
            "http://example.com\\path",
            "http://example.com./",
            "http://192.168.1/",
            "http://0x7f.0.0.1/",
            "http://example.123/",
            "http://-bad.example.com/",
            "http://exa_mple.com/",
            "http://exam%70le.com/",
            "example.com/no-scheme",
            "   ",
            "http://example.com/\u0007"})
    void malformedOrForbiddenUrlShapesAreDenied(String url) {
        assertThatThrownBy(() -> BrowserDestinationPolicy.origin(url))
                .isInstanceOf(BrowserRejectedException.class)
                .satisfies(
                        failure -> assertThat(((BrowserRejectedException) failure).code())
                                .isEqualTo("destination_denied"));
    }

    @Test
    void privateOriginsAreAllowedExceptForOperatorRoutes() {
        var policy = new BrowserDestinationPolicy(
                List.of(),
                List.of("http://localhost:8080"),
                ROUTES,
                NOTHING_RESERVED);
        assertThatCode(() -> policy.requireAllowed("http://localhost:8080/console"))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy.requireAllowed("http://LOCALHOST:8080/api/operator/../public"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.requireAllowed("http://localhost:8080/api/operator/status"))
                .hasMessage("Local operator API routes are unavailable to browser tools.");
        assertThatThrownBy(() -> policy.requireAllowed("http://localhost:8080/velocity/api/agent/executions"))
                .hasMessage("Local operator API routes are unavailable to browser tools.");
        assertThatThrownBy(() -> policy.requireAllowed("http://localhost:8080/api/operator;x=1/status"))
                .hasMessage("Local operator API routes are unavailable to browser tools.");
    }

    @Test
    void internalNamesNeedAnExplicitOriginOverride() {
        var policy = new BrowserDestinationPolicy(List.of(), List.of(), ROUTES, NOTHING_RESERVED);
        for (String url : List.of(
                "http://localhost:9090/",
                "http://intranet/",
                "http://service.internal/",
                "http://printer.lan/",
                "http://nas.home/",
                "http://app.test/")) {
            assertThatThrownBy(() -> policy.requireAllowed(url))
                    .hasMessage(
                            "Private and local browser destinations require an explicit origin override.");
        }
    }

    @Test
    void addressLiteralsAreClassifiedWithoutDns() {
        var policy = new BrowserDestinationPolicy(List.of(), List.of(), ROUTES, NOTHING_RESERVED);
        assertThatCode(() -> policy.requireAllowed("http://93.184.216.34/")).doesNotThrowAnyException();
        assertThatCode(() -> policy.requireAllowed("https://[2606:4700::1111]/")).doesNotThrowAnyException();
        for (String url : List.of(
                "http://10.0.0.1/",
                "http://169.254.169.254/latest/meta-data/",
                "http://127.0.0.1:8080/",
                "http://[::1]/",
                "http://[fd00::1]/",
                "http://[2001:db8::1]/")) {
            assertThatThrownBy(() -> policy.requireAllowed(url))
                    .hasMessage("Private, local, reserved, and metadata browser destinations are blocked.");
        }
    }

    @Test
    void allowlistAndReservationsAreCheckedBeforeResolution() {
        var allowlisted = new BrowserDestinationPolicy(
                List.of("93.184.216.34"),
                List.of(),
                ROUTES,
                NOTHING_RESERVED);
        assertThatCode(() -> allowlisted.requireAllowed("http://93.184.216.34/")).doesNotThrowAnyException();
        assertThatThrownBy(() -> allowlisted.requireAllowed("http://example.org/"))
                .hasMessage("The destination host is outside the configured browser allowlist.");

        ReservedDestinationPolicy mail = new ReservedDestinationPolicy() {

            @Override
            public boolean browserHostBlocked(String host) {
                return "93.184.216.34".equals(host);
            }

            @Override
            public boolean credentialDestinationBlocked(String destination) {
                return false;
            }
        };
        var reserved = new BrowserDestinationPolicy(
                List.of("93.184.216.34"),
                List.of("http://93.184.216.34"),
                ROUTES,
                new ReservedDestinations(List.of(mail)));
        assertThatThrownBy(() -> reserved.requireAllowed("http://93.184.216.34/"))
                .hasMessage(
                        "The destination is reserved for a server-owned integration and is unavailable to the browser.");
    }

    @Test
    void publicAddressClassificationExcludesSpecialPurposeRanges() throws Exception {
        for (String address : List.of("8.8.8.8", "93.184.216.34", "172.32.0.1", "2606:4700::1111")) {
            assertThat(BrowserDestinationPolicy.isPublic(InetAddress.getByName(address))).as(address)
                    .isTrue();
        }
        for (String address : List.of(
                "0.0.0.0",
                "10.1.2.3",
                "100.64.0.1",
                "127.0.0.1",
                "169.254.10.10",
                "172.16.0.1",
                "192.0.0.1",
                "192.0.2.1",
                "192.88.99.1",
                "192.168.1.1",
                "198.18.0.1",
                "198.51.100.7",
                "203.0.113.9",
                "224.0.0.1",
                "168.63.129.16",
                "::",
                "::1",
                "fe80::1",
                "fc00::1",
                "fd12::1",
                "ff02::1",
                "2002::1",
                "2001::1",
                "2001:db8::1",
                "3ffe::1",
                "64:ff9b::1")) {
            assertThat(BrowserDestinationPolicy.isPublic(InetAddress.getByName(address))).as(address)
                    .isFalse();
        }
    }

    @Test
    void sameSourceComparesOriginPathAndQueryButNotFragments() {
        assertThat(
                BrowserDestinationPolicy.sameSource(
                        "https://example.com/a?x=1#one",
                        "https://example.com:443/a?x=1#two"))
                .isTrue();
        assertThat(BrowserDestinationPolicy.sameSource("https://example.com", "https://example.com/"))
                .isTrue();
        assertThat(
                BrowserDestinationPolicy.sameSource("https://example.com/a?x=1", "https://example.com/a?x=2"))
                .isFalse();
        assertThat(BrowserDestinationPolicy.sameSource("https://example.com/a", "https://example.com/b"))
                .isFalse();
        assertThat(BrowserDestinationPolicy.sameSource("https://example.com/a", "http://example.com/a"))
                .isFalse();
        assertThat(BrowserDestinationPolicy.sameSource("https://u@example.com/a", "https://example.com/a"))
                .isFalse();
        assertThat(BrowserDestinationPolicy.sameSource("not a url", "https://example.com/a")).isFalse();
    }

    @Test
    void safeUrlsDropQueriesFragmentsAndUnparseableInput() {
        assertThat(BrowserDestinationPolicy.safeUrl("https://example.com/path?token=abc#frag"))
                .isEqualTo("https://example.com/path");
        assertThat(BrowserDestinationPolicy.safeUrl("https://example.com")).isEqualTo("https://example.com/");
        assertThat(BrowserDestinationPolicy.safeUrl("ftp://example.com/x")).isEqualTo("[URL withheld]");
        String longPath = "https://example.com/" + "a".repeat(3_000);
        assertThat(BrowserDestinationPolicy.safeUrl(longPath)).hasSize(2_049).endsWith("…");
    }

    @Test
    void configuredHostsAreExactHostNamesOrAddressLiterals() {
        assertThat(BrowserDestinationPolicy.configuredHost("Example.COM")).isEqualTo("example.com");
        assertThat(BrowserDestinationPolicy.configuredHost("::1")).isEqualTo("::1");
        assertThat(BrowserDestinationPolicy.configuredHost("[::1]")).isEqualTo("::1");
        assertThat(BrowserDestinationPolicy.configuredHost("10.0.0.1")).isEqualTo("10.0.0.1");
        for (String value : List.of("example.com:8080", "*.example.com", "example.com/path", "", "a b")) {
            assertThatThrownBy(() -> BrowserDestinationPolicy.configuredHost(value))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exact host names");
        }
    }

    @Test
    void configuredOriginsAreExactHttpOrigins() {
        assertThat(BrowserDestinationPolicy.configuredOrigin("http://localhost:8080"))
                .isEqualTo("http://localhost:8080");
        assertThat(BrowserDestinationPolicy.configuredOrigin("http://localhost:8080/"))
                .isEqualTo("http://localhost:8080");
        assertThat(BrowserDestinationPolicy.configuredOrigin("https://Intranet.Example:443"))
                .isEqualTo("https://intranet.example");
        for (String value : List.of(
                "http://localhost:8080/path",
                "http://localhost:8080/?q",
                "http://localhost:8080/#f",
                "http://user@localhost:8080",
                "localhost:8080",
                "ftp://localhost")) {
            assertThatThrownBy(() -> BrowserDestinationPolicy.configuredOrigin(value))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exact HTTP(S) origins");
        }
    }
}
