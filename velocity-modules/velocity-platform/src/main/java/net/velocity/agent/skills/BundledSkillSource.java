package net.velocity.agent.skills;

import java.util.List;

/**
 * Contributes bundled skills to the operator's fixed catalog. Declare it as a dependency-free {@code static} bean in
 * a configuration class that is always loaded with the feature.
 *
 * <p>Contract, enforced by {@link BundledSkills} when the operator skills controller is created at startup:
 *
 * <ul>
 *   <li>Bundle ids are unique across all sources; a duplicate fails startup. Each {@link BundledSkill} validates its
 *       own identity and text.
 *   <li>The catalog lists bundles in Spring order ({@code @Order} on the bean method, lower first; unordered beans
 *       last), then in the order each source returns them. Give every source an explicit {@code @Order}; the order
 *       is visible in the operator's bundle list and in the install request schema.
 *   <li>Released versions are immutable (see {@link BundledSkill}). No model tool installs a bundle; only the
 *       trusted local operator adapter selects from the catalog.
 * </ul>
 */
@FunctionalInterface
public interface BundledSkillSource {

    List<BundledSkill> bundles();
}
