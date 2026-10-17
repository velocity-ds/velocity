package net.velocity.agent.security;

import java.util.regex.Pattern;

/**
 * A protected local-operator route root: the root itself and everything beneath it.
 *
 * <p>A root is a literal, already-normalized servlet path of at least two segments starting with {@code /api},
 * for example {@code /api/operator}. Segments consist of letters, digits, {@code _} and {@code -}. Anything else
 * (wildcards, templates, {@code ;}, {@code %}, {@code .}, {@code ..}, empty segments, a trailing slash) is rejected
 * so a root always means exactly what it says.
 *
 * @param root literal root, for example {@code /api/operator/secrets}
 * @param sensitive also marks every path under the root as secret-bearing: its responses are not cacheable and
 *        its request bodies are zeroed after use
 */
public record OperatorRoute(String root, boolean sensitive) {

    private static final Pattern SHAPE = Pattern.compile("/api(/[A-Za-z0-9][A-Za-z0-9_-]*)+");

    public OperatorRoute {
        if (root == null || !SHAPE.matcher(root).matches()) {
            throw new IllegalArgumentException("Invalid operator route root: " + root);
        }
    }

    /** A protected root whose paths are not marked sensitive. */
    public static OperatorRoute of(String root) {
        return new OperatorRoute(root, false);
    }

    /** A protected root whose paths are marked sensitive. */
    public static OperatorRoute secret(String root) {
        return new OperatorRoute(root, true);
    }
}
