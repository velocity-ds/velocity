package net.velocity.agent.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import net.velocity.agent.spi.ReservedDestinationPolicy;

/**
 * The destinations reserved from the browser and from website credentials: the union of every
 * {@link ReservedDestinationPolicy} bean. A destination is reserved when any policy reserves it, and a policy that
 * throws reserves it (fail closed). With no contributed policy nothing is reserved.
 */
public final class ReservedDestinations {

    private final List<ReservedDestinationPolicy> policies;

    public ReservedDestinations(List<ReservedDestinationPolicy> contributed) {
        var all = new ArrayList<ReservedDestinationPolicy>();
        for (ReservedDestinationPolicy policy : Objects.requireNonNull(contributed, "Policies")) {
            all.add(Objects.requireNonNull(policy, "Policy"));
        }
        this.policies = List.copyOf(all);
    }

    /** All methods and paths, including batch endpoints; allowlist entries cannot override this. */
    public boolean browserHostBlocked(String host) {
        for (ReservedDestinationPolicy policy : policies) {
            try {
                if (policy.browserHostBlocked(host)) {
                    return true;
                }
            } catch (RuntimeException failure) {
                return true;
            }
        }
        return false;
    }

    /** Reserved integration credentials and shared sign-in receivers are not website pairs. */
    public boolean credentialDestinationBlocked(String destination) {
        for (ReservedDestinationPolicy policy : policies) {
            try {
                if (policy.credentialDestinationBlocked(destination)) {
                    return true;
                }
            } catch (RuntimeException failure) {
                return true;
            }
        }
        return false;
    }
}
