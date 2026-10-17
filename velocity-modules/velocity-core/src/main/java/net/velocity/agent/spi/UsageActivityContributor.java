package net.velocity.agent.spi;

import java.util.List;

import net.velocity.usage.UsageActivities;

/**
 * Contributes usage activity names that a feature's inference requests are recorded under. Declare it as a
 * dependency-free {@code static} bean in a configuration class that is always loaded with the feature, whether or
 * not the feature is enabled, so stored records of that activity stay readable.
 *
 * <p>Contract, enforced by {@link UsageActivities} when the context starts:
 *
 * <ul>
 *   <li>Each name matches {@code [a-z][a-z_]{1,39}}, is not one of the core names, and is contributed once across
 *       all contributors; otherwise startup fails.
 *   <li>Names are persisted in every usage record and used as report filters, so a released name is permanent:
 *       never rename or remove it while stored records may carry it.
 *   <li>The order of contributors has no effect on the result. A shipped contributor still carries an explicit
 *       {@code @Order} so the injected list is the same whichever module it comes from.
 * </ul>
 */
@FunctionalInterface
public interface UsageActivityContributor {

    List<String> activities();
}
