package net.velocity.agent.spi;

import net.velocity.agent.security.ReservedDestinations;

/**
 * Reserves destinations for a server-owned integration, so neither the browser nor a saved website credential can
 * reach them. Contribute one as a dependency-free {@code static} bean in a configuration class that is always loaded
 * with the feature.
 *
 * <p>Contract, enforced by {@link ReservedDestinations}:
 *
 * <ul>
 *   <li>A destination is reserved when any policy reserves it: contributions can only add restrictions, and no
 *       allowlist or private-origin setting overrides them.
 *   <li>Fail closed: a policy that throws reserves the destination.
 *   <li>Implementations must be total, side-effect free and fast; they run for every browser request.
 *   <li>Order has no effect.
 * </ul>
 *
 * A feature that owns a hosted mailbox, ledger or similar account surface contributes one so the browser and saved
 * logins cannot reach that surface behind the integration's back. The kernel reserves nothing by itself.
 */
public interface ReservedDestinationPolicy {

    /** Whether the browser (and the document downloader) must refuse this destination host, on every path. */
    boolean browserHostBlocked(String host);

    /** Whether this URL may not be saved as a website credential origin, callback or resource. */
    boolean credentialDestinationBlocked(String destination);
}
