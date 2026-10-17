package net.velocity.agent.spi;

import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * One top-level section of the operator status document ({@code GET /api/operator/status}).
 *
 * <p>Contract, enforced by {@code OperatorStatusController} when the context starts:
 *
 * <ul>
 *   <li>Keys are camel-case JSON property names matching {@code [a-z][A-Za-z0-9]*} and are unique across all
 *       contributors; a duplicate or malformed key fails startup.
 *   <li>The document lists the sections in Spring order ({@code @Order} on the bean method, lower first; unordered
 *       beans last). Give every contributor an explicit {@code @Order}; the values in use are in
 *       {@code docs/agent-gateway.md}. The order is visible to operators because it fixes the key order of the JSON.
 *   <li>{@link #section()} runs once per request, must not block, perform network access or throw, and returns an
 *       explicit allowlist of safe flags and counts: never configuration beans, credentials, key material, hosts or
 *       exception messages. A {@code null} section is still written under its key.
 *   <li>A feature contributes its section whether or not it is enabled, so a disabled feature reports
 *       {@code enabled: false} rather than disappearing. Declare the bean in a configuration class that is always
 *       loaded with the feature.
 * </ul>
 */
public interface SystemStatusContributor {

    Pattern KEY = Pattern.compile("[a-z][A-Za-z0-9]*");

    String key();

    Object section();

    /** A contributor whose section is computed by {@code section} on every request. */
    static SystemStatusContributor of(String key, Supplier<?> section) {
        Objects.requireNonNull(section);
        return new SystemStatusContributor() {

            @Override
            public String key() {
                return key;
            }

            @Override
            public Object section() {
                return section.get();
            }
        };
    }
}
