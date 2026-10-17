package net.velocity.web;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP liveness only. Provider, storage, and integration readiness are checked separately. */
@RestController
public final class HttpLivenessController {

    @GetMapping(value = "/velocity/hello", produces = MediaType.TEXT_PLAIN_VALUE)
    String hello() {
        return "Hello from Velocity!";
    }
}
