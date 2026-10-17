package net.velocity.agent.spi;

import net.velocity.agent.AgentGateway;

/**
 * Trusted installation of lifecycle hooks on the {@link AgentGateway}, for example deletion barriers, deletion
 * listeners and settlement checks owned by a feature.
 *
 * <p>Contract:
 *
 * <ul>
 *   <li><b>Timing.</b> Customizers run once, while the gateway bean is built, after the core wiring and
 *       <em>before</em> the bean's {@code recover} init method, so a hook is always in place before any
 *       persisted execution is recovered.
 *   <li><b>Ordering.</b> Spring order ({@code @Order} on the bean method, lower first; unordered beans last).
 *       Hooks of the same kind run in registration order (for example every deletion barrier runs in sequence
 *       before any deletion listener), so a customizer's position among others of the same kind is part of the
 *       contract. The order values in use are listed in {@code docs/agent-gateway.md}.
 *   <li><b>Failure.</b> An exception aborts the gateway bean and therefore application startup.
 *   <li>A customizer must only call the gateway's {@code register*} methods. It must not call the validator or
 *       admission-learning setters, which replace core wiring and run after it; this is not enforced.
 * </ul>
 */
@FunctionalInterface
public interface GatewayCustomizer {

    void customize(AgentGateway gateway);
}
