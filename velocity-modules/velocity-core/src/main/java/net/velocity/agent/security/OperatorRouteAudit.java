package net.velocity.agent.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

import net.velocity.agent.spi.OperatorRouteContributor;

/**
 * Deny-by-default startup audit: the application context fails to start when any request mapping whose path begins
 * with {@code /api} is not under a protected {@link OperatorRoutes} root, so a new controller can never be served
 * outside the local operator boundary by accident.
 *
 * <p>Rules, applied to every pattern of every {@link RequestMappingInfoHandlerMapping} bean:
 *
 * <ul>
 *   <li>A pattern is audited when its first segment is {@code api} (compared ignoring case) or is not a plain
 *       literal (a template, wildcard or regular expression, which could match {@code /api/...}).
 *   <li>The audited pattern's literal leading segments, up to the first segment that is not a plain literal, must
 *       equal a protected root or lie under one. {@code /api/operator/tasks/{id}} is covered by
 *       {@code /api/operator}; {@code /api/foo}, {@code /api/{x}} and {@code /{x}} are not.
 *   <li>Patterns outside {@code /api} (for example the console pages and {@code /velocity/hello}) are public by
 *       design and are not audited.
 * </ul>
 *
 * <p>Only request mappings are audited. Static resource handlers, functional routes and bean-name URL mappings are
 * not request mappings; the application defines none under {@code /api}. When no request mapping bean exists (a
 * non-web context) nothing is audited. The fix for a violation is to mount the controller under
 * {@code /api/operator/<feature>} or to contribute its root through an {@link OperatorRouteContributor}.
 */
public final class OperatorRouteAudit implements SmartInitializingSingleton {

    private final OperatorRoutes routes;
    private final ObjectProvider<RequestMappingInfoHandlerMapping> mappings;

    public OperatorRouteAudit(
            OperatorRoutes routes,
            ObjectProvider<RequestMappingInfoHandlerMapping> mappings) {
        this.routes = Objects.requireNonNull(routes);
        this.mappings = Objects.requireNonNull(mappings);
    }

    @Override
    public void afterSingletonsInstantiated() {
        var violations = new TreeSet<String>();
        mappings.orderedStream().forEach(mapping -> {
            for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mapping.getHandlerMethods()
                    .entrySet()) {
                violations.addAll(violations(routes, entry.getKey(), String.valueOf(entry.getValue())));
            }
        });
        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "Request mappings under /api are outside every protected operator route root "
                            + routes.roots() + ": " + String.join("; ", violations)
                            + ". Mount the controller under /api/operator/<feature> or contribute an"
                            + " OperatorRouteContributor root.");
        }
    }

    /** The audit's judgment of one mapping: a description of each unprotected pattern, or an empty list. */
    static List<String> violations(OperatorRoutes routes, RequestMappingInfo info, String handler) {
        var found = new ArrayList<String>();
        for (String pattern : info.getPatternValues()) {
            if (!covered(routes, pattern)) {
                found.add(info.getMethodsCondition().getMethods() + " " + pattern + " -> " + handler);
            }
        }
        return found;
    }

    /** Whether the pattern is outside the audited space or its literal prefix is under a protected root. */
    static boolean covered(OperatorRoutes routes, String pattern) {
        String relative = pattern.startsWith("/") ? pattern.substring(1) : pattern;
        if (relative.isEmpty()) {
            return true;
        }
        List<String> segments = List.of(relative.split("/", -1));
        boolean first = true;
        var literal = new ArrayList<String>();
        for (String segment : segments) {
            if (!plainLiteral(segment)) {
                break;
            }
            literal.add(segment);
            first = false;
        }
        if (first) {
            // The first segment is not a plain literal: it could match /api/..., which no root can cover.
            return false;
        }
        if (!"api".equalsIgnoreCase(literal.getFirst())) {
            return true;
        }
        String prefix = "/" + String.join("/", literal);
        return OperatorPaths.normalize(prefix).equals(prefix) && routes.protectedServletPath(prefix);
    }

    private static boolean plainLiteral(String segment) {
        return !segment.isEmpty() && segment.chars().noneMatch(c -> "{}*?[]\\%;".indexOf(c) >= 0);
    }
}
