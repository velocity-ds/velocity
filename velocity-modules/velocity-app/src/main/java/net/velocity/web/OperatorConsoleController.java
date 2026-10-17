package net.velocity.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Explicit SPA routes; unknown paths and API failures never fall back to HTML. */
@Controller
public final class OperatorConsoleController {

    @GetMapping({"/", "/tasks", "/tasks/{id}", "/knowledge", "/secrets", "/schedules", "/monitoring",
            "/playground", "/system", "/usage"})
    String index() {
        return "forward:/index.html";
    }
}
