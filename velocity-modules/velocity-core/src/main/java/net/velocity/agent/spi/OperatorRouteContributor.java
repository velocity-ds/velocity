package net.velocity.agent.spi;

import java.util.List;

import net.velocity.agent.security.OperatorRoute;
import net.velocity.agent.security.OperatorRouteAudit;
import net.velocity.agent.security.OperatorRoutes;

/**
 * Contributes protected local-operator route roots. Declare it as a dependency-free bean (a {@code static} bean
 * method returning a lambda) in a configuration class that is always loaded with the feature, whether or not the
 * feature is enabled: a disabled feature's root stays protected.
 *
 * <p>Contract, enforced by {@link OperatorRoutes} when the application context starts:
 *
 * <ul>
 *   <li>A contribution can only add protection. Roots and sensitivity accumulate across contributors; a root named
 *       by several contributors is protected once and is sensitive if any contributor marks it so.
 *   <li>Every root must be a valid {@link OperatorRoute}; an invalid root fails startup.
 *   <li>The order of contributors has no effect on the result. A shipped contributor still carries an explicit
 *       {@code @Order} so the injected list is the same whichever module it comes from.
 *   <li>The core roots ({@code /api/operator}, {@code /api/agent/executions}, {@code /api/agent/knowledge}, and the
 *       sensitive {@code /api/operator/secrets}) are always protected and need no contribution. A feature that
 *       mounts its controllers under {@code /api/operator/<feature>} needs no contribution either; one that mounts
 *       outside {@code /api/operator} must contribute its root, or the startup audit
 *       ({@link OperatorRouteAudit}) fails the context.
 *   <li>Contributing a sensitive root that lies under another protected root (the usual case) only adds the
 *       sensitive handling.
 * </ul>
 */
@FunctionalInterface
public interface OperatorRouteContributor {

    List<OperatorRoute> routes();
}
