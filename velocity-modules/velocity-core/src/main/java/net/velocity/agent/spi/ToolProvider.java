package net.velocity.agent.spi;

import net.velocity.agent.AuthorizationPolicy;
import net.velocity.agent.ToolRegistry;

/**
 * Trusted Java registrations contributed by a feature, collected once while the {@link ToolRegistry} bean is built.
 *
 * <p>Contract:
 *
 * <ul>
 *   <li><b>Ordering.</b> Providers run in Spring order ({@code @Order} on the bean method, lower first; beans
 *       without an order run last, in bean-definition order) after the core registrations. Registration order is
 *       not model-visible ({@link ToolRegistry#definitions()} is name-sorted), but it is kept stable. The order
 *       values in use are listed in {@code docs/agent-gateway.md}.
 *   <li><b>Uniqueness.</b> Tool names are unique; a duplicate makes {@link ToolRegistry#register} throw and the
 *       application context fail to start, exactly as before.
 *   <li><b>Disabled features.</b> A provider decides for itself whether its feature is enabled and registers
 *       nothing when it is not; a disabled feature contributes no tool.
 *   <li><b>Failure.</b> An exception aborts the registry bean and therefore application startup.
 *   <li><b>Authority.</b> Registering a tool grants nothing: every call still passes the
 *       {@link AuthorizationPolicy}, schema validation and scope checks.
 * </ul>
 */
@FunctionalInterface
public interface ToolProvider {

    void register(ToolRegistry registry);
}
