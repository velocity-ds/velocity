package net.velocity.agent.security;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;

import org.springframework.web.util.UriUtils;

/**
 * Canonical MVC path shape, independent of authorization or destination decisions. The protected roots are
 * {@link OperatorRoutes}.
 */
public final class OperatorPaths {

    private OperatorPaths() {
    }

    public static String normalize(String raw) {
        String decoded = UriUtils.decode(raw, StandardCharsets.UTF_8).replaceAll(";[^/]*", "");
        var segments = new ArrayDeque<String>();
        for (String segment : decoded.split("/")) {
            if ("..".equals(segment)) {
                if (!segments.isEmpty()) {
                    segments.removeLast();
                }
            } else if (!segment.isEmpty() && !".".equals(segment)) {
                segments.addLast(segment);
            }
        }
        return "/" + String.join("/", segments);
    }
}
