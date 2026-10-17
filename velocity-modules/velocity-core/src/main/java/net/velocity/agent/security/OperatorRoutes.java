package net.velocity.agent.security;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import net.velocity.agent.spi.OperatorRouteContributor;

/**
 * The immutable set of protected local-operator route roots: the core roots below plus every root contributed by an
 * {@link OperatorRouteContributor}.
 *
 * <p>It answers the three questions the application asks about a path:
 *
 * <ul>
 *   <li>{@link #protectedServletPath}: must the local operator boundary (loopback peer, same-origin host and
 *       origin, body limit) be enforced for an inbound request;
 *   <li>{@link #protectedBrowserPath}: must a browser or downloader be refused the destination, because it would
 *       reach an operator route on a private origin;
 *   <li>{@link #sensitive}: do the response and request body carry secrets.
 * </ul>
 *
 * <p>Both protection answers come from the same set, so inbound and outbound protection cannot diverge. The set is
 * a bean, not static state: nothing can register a root after startup, and a policy built without routes does not
 * exist. The core roots cannot be omitted or replaced by a contribution. See {@link OperatorRouteContributor} for
 * the contribution rules.
 */
public final class OperatorRoutes {

    /** Roots owned by the core application; always protected. */
    static final List<OperatorRoute> CORE = List.of(
            OperatorRoute.of("/api/operator"),
            OperatorRoute.of("/api/agent/executions"),
            OperatorRoute.of("/api/agent/knowledge"),
            OperatorRoute.secret("/api/operator/secrets"));

    private final Set<String> roots;
    private final Set<String> sensitiveRoots;

    public OperatorRoutes(List<OperatorRouteContributor> contributors) {
        var merged = new TreeMap<String, Boolean>();
        CORE.forEach(route -> merged.merge(route.root(), route.sensitive(), Boolean::logicalOr));
        for (OperatorRouteContributor contributor : Objects.requireNonNull(contributors, "Contributors")) {
            List<OperatorRoute> routes = Objects.requireNonNull(contributor, "Contributor").routes();
            for (OperatorRoute route : Objects.requireNonNull(routes, "Contributed routes")) {
                Objects.requireNonNull(route, "Contributed route");
                merged.merge(route.root(), route.sensitive(), Boolean::logicalOr);
            }
        }
        var protectedRoots = new TreeSet<String>();
        var secretRoots = new TreeSet<String>();
        merged.forEach((root, sensitive) -> {
            if (!OperatorPaths.normalize(root).equals(root)) {
                throw new IllegalArgumentException("Operator route root is not normalized: " + root);
            }
            protectedRoots.add(root);
            if (sensitive) {
                secretRoots.add(root);
            }
        });
        this.roots = Set.copyOf(protectedRoots);
        this.sensitiveRoots = Set.copyOf(secretRoots);
    }

    /** Protected roots, sorted. */
    public List<String> roots() {
        return roots.stream().sorted().toList();
    }

    /** Roots whose paths are sensitive, sorted. */
    public List<String> sensitiveRoots() {
        return sensitiveRoots.stream().sorted().toList();
    }

    /** Servlet callers already removed the configured context. */
    public boolean protectedServletPath(String normalized) {
        return underAny(roots, normalized);
    }

    /** Browser callers cannot know the servlet context; recognize protected roots after any context prefix. */
    public boolean protectedBrowserPath(String normalized) {
        String path = normalized + "/";
        return roots.stream().anyMatch(root -> path.contains(root + "/"));
    }

    /** Whether the normalized servlet path is a sensitive root or lies under one. */
    public boolean sensitive(String normalized) {
        return underAny(sensitiveRoots, normalized);
    }

    private static boolean underAny(Set<String> candidates, String normalized) {
        return candidates.stream()
                .anyMatch(root -> normalized.equals(root) || normalized.startsWith(root + "/"));
    }
}
