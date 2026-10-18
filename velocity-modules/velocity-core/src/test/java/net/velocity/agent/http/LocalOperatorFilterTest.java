package net.velocity.agent.http;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import net.velocity.agent.config.UploadProperties;
import net.velocity.agent.security.OperatorRoutes;

import jakarta.servlet.http.HttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class LocalOperatorFilterTest {

    private final OperatorRoutes routes = new OperatorRoutes(List.of());
    private final UploadProperties uploads = new UploadProperties(true, 10, 1_024, 2_000_000);
    private final LocalOperatorFilter filter = new LocalOperatorFilter(routes, uploads);

    @Test
    void loopbackPeerWithMatchingHostIsAuthorized() {
        assertThat(LocalOperatorFilter.authorizedBoundary(request("127.0.0.1", "localhost:8080"))).isTrue();
        assertThat(LocalOperatorFilter.authorizedBoundary(request("127.0.0.1", "127.0.0.1"))).isTrue();
        assertThat(LocalOperatorFilter.authorizedBoundary(request("0:0:0:0:0:0:0:1", "[::1]:8080"))).isTrue();
        assertThat(LocalOperatorFilter.authorizedBoundary(request("::1", "LOCALHOST:8080"))).isTrue();
    }

    @Test
    void peerAuthorityComesFromTheSocketNeverFromHeaders() {
        var forwarded = request("10.0.0.5", "localhost:8080");
        forwarded.addHeader("X-Forwarded-For", "127.0.0.1");
        assertThat(LocalOperatorFilter.authorizedBoundary(forwarded)).isFalse();
        assertThat(LocalOperatorFilter.authorizedBoundary(request("localhost", "localhost:8080"))).isFalse();
        assertThat(LocalOperatorFilter.authorizedBoundary(request("fe80::1%eth0", "localhost:8080")))
                .isFalse();
        assertThat(LocalOperatorFilter.authorizedBoundary(request("127.0.0.1", "intranet.example:8080")))
                .isFalse();
    }

    @Test
    void hostHeaderMustBeASingleAuthority() {
        var request = request("127.0.0.1", "localhost:8080");
        request.addHeader("Host", "localhost:8081");
        assertThat(LocalOperatorFilter.authorizedBoundary(request)).isFalse();
        assertThat(LocalOperatorFilter.authorizedBoundary(request("127.0.0.1", "localhost:8080/path")))
                .isFalse();
        assertThat(LocalOperatorFilter.authorizedBoundary(request("127.0.0.1", "user@localhost:8080")))
                .isFalse();
        var missing = new MockHttpServletRequest("GET", "/api/operator/status");
        missing.setRemoteAddr("127.0.0.1");
        assertThat(LocalOperatorFilter.authorizedBoundary(missing)).isFalse();
    }

    @Test
    void originMustMatchTheHostSchemeAndPort() {
        assertThat(LocalOperatorFilter.authorizedBoundary(origin("localhost:8080", "http://localhost:8080")))
                .isTrue();
        assertThat(LocalOperatorFilter.authorizedBoundary(origin("127.0.0.1", "http://127.0.0.1"))).isTrue();
        assertThat(LocalOperatorFilter.authorizedBoundary(origin("localhost:8080", "http://localhost:9090")))
                .isFalse();
        assertThat(LocalOperatorFilter.authorizedBoundary(origin("localhost:8080", "http://localhost")))
                .isFalse();
        assertThat(LocalOperatorFilter.authorizedBoundary(origin("localhost:8080", "https://localhost:8080")))
                .isFalse();
        assertThat(LocalOperatorFilter.authorizedBoundary(origin("localhost:8080", "http://evil.example")))
                .isFalse();
        assertThat(LocalOperatorFilter.authorizedBoundary(origin("localhost:8080", "null"))).isFalse();
        var secure = origin("localhost:8443", "https://localhost:8443");
        secure.setSecure(true);
        assertThat(LocalOperatorFilter.authorizedBoundary(secure)).isTrue();
        var twoOrigins = origin("localhost:8080", "http://localhost:8080");
        twoOrigins.addHeader("Origin", "http://localhost:8080");
        assertThat(LocalOperatorFilter.authorizedBoundary(twoOrigins)).isFalse();
    }

    @Test
    void crossSiteFetchMetadataIsRefused() {
        var crossSite = request("127.0.0.1", "localhost:8080");
        crossSite.addHeader("Sec-Fetch-Site", "cross-site");
        assertThat(LocalOperatorFilter.authorizedBoundary(crossSite)).isFalse();
        var sameOrigin = request("127.0.0.1", "localhost:8080");
        sameOrigin.addHeader("Sec-Fetch-Site", "same-origin");
        assertThat(LocalOperatorFilter.authorizedBoundary(sameOrigin)).isTrue();
    }

    @Test
    void onlyProtectedRoutesAreFilteredAfterPathNormalization() {
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/operator/status")))
                .isFalse();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/agent/executions;x=1")))
                .isFalse();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/%6fperator/status")))
                .isFalse();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/velocity/hello"))).isTrue();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/operators"))).isTrue();
        assertThat(
                filter.shouldNotFilter(
                        new MockHttpServletRequest("GET", "/api/operator/../../velocity/hello")))
                .isTrue();
        // An undecodable path is filtered rather than let through.
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/%zz"))).isFalse();
    }

    @Test
    void unprotectedRoutesPassThroughUntouched() throws Exception {
        var request = new MockHttpServletRequest("GET", "/velocity/hello");
        request.setRemoteAddr("10.0.0.5");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void unauthorizedPeersGetForbiddenAndSecretRoutesAreNeverCached() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/operator/secrets");
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("Host", "localhost:8080");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("Pragma")).isEqualTo("no-cache");

        var plain = new MockHttpServletRequest("GET", "/api/operator/status");
        plain.setRemoteAddr("10.0.0.5");
        plain.addHeader("Host", "localhost:8080");
        var plainResponse = new MockHttpServletResponse();
        filter.doFilter(plain, plainResponse, new MockFilterChain());
        assertThat(plainResponse.getStatus()).isEqualTo(403);
        assertThat(plainResponse.getHeader("Cache-Control")).isNull();
    }

    @Test
    void undecodablePathsAreTreatedAsSensitive() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/%zz");
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("Host", "localhost:8080");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void authorizedRequestsAreBufferedSoTheBodyCanBeReadAgain() throws Exception {
        var request = request("127.0.0.1", "localhost:8080");
        byte[] body = "{\"text\":\"hi\"}".getBytes();
        request.setContent(body);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        var buffered = (HttpServletRequest) chain.getRequest();
        assertThat(buffered).isNotSameAs(request);
        assertThat(buffered.getContentLengthLong()).isEqualTo(body.length);
        assertThat(buffered.getInputStream().readAllBytes()).isEqualTo(body);
        assertThat(buffered.getInputStream().readAllBytes()).isEqualTo(body);
    }

    @Test
    void bodiesAboveTheCeilingAreRejectedExceptForUploads() throws Exception {
        byte[] body = new byte[LocalOperatorFilter.MAX_BODY_BYTES + 1];

        var status = request("127.0.0.1", "localhost:8080");
        status.setContent(body);
        var statusResponse = new MockHttpServletResponse();
        filter.doFilter(status, statusResponse, new MockFilterChain());
        assertThat(statusResponse.getStatus()).isEqualTo(413);

        var upload = new MockHttpServletRequest("POST", LocalOperatorFilter.UPLOAD_ROUTE);
        upload.setRemoteAddr("127.0.0.1");
        upload.addHeader("Host", "localhost:8080");
        upload.setContent(body);
        var uploadResponse = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilter(upload, uploadResponse, chain);
        assertThat(uploadResponse.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();

        var read = new MockHttpServletRequest("GET", LocalOperatorFilter.UPLOAD_ROUTE);
        read.setRemoteAddr("127.0.0.1");
        read.addHeader("Host", "localhost:8080");
        read.setContent(body);
        var readResponse = new MockHttpServletResponse();
        filter.doFilter(read, readResponse, new MockFilterChain());
        assertThat(readResponse.getStatus()).isEqualTo(413);
    }

    private static MockHttpServletRequest request(String remoteAddress, String host) {
        var request = new MockHttpServletRequest("GET", "/api/operator/status");
        request.setRemoteAddr(remoteAddress);
        request.addHeader("Host", host);
        return request;
    }

    private static MockHttpServletRequest origin(String host, String origin) {
        var request = request("127.0.0.1", host);
        request.addHeader("Origin", origin);
        return request;
    }
}
