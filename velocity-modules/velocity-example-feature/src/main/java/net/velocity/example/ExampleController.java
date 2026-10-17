package net.velocity.example;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import net.velocity.agent.spi.OperatorRouteContributor;

import jakarta.servlet.http.HttpServletRequest;

/**
 * TEMPLATE: a read-only operator route.
 *
 * <p>Route convention: mount a feature's API under {@code /api/operator/<feature>}. That path is inside the
 * protected {@code /api/operator} root, so {@code LocalOperatorFilter} already applies its loopback peer, Host,
 * Origin and body-size checks and no {@code OperatorRouteContributor} is needed. A mapping anywhere else under
 * {@code /api} that no root covers fails startup (the route audit). A feature that needs a different root
 * contributes an {@code OperatorRouteContributor} instead.
 *
 * <p>Request handling is strict: this route takes no query string and no body, and responds {@code no-store}.
 * A route that acts on a user's resources would also resolve the owner through {@code LocalAccountScope}
 * (never from the request); this one exposes only static, safe flags, so it needs no account.
 */
@RestController
public final class ExampleController {

    private final ExampleProperties properties;

    public ExampleController(ExampleProperties properties) {
        this.properties = properties;
    }

    public record View(boolean enabled, String tool) {}

    @GetMapping("/api/operator/example")
    ResponseEntity<View> example(HttpServletRequest request) throws IOException {
        if (request.getQueryString() != null || request.getInputStream().read() != -1) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "The example route accepts no body or query parameters");
        }
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new View(properties.enabled(), ExampleTools.ECHO));
    }
}
