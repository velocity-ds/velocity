package net.velocity.agent.spi;

import net.velocity.agent.AuthorizationPolicy;

/**
 * Narrows the application's {@link AuthorizationPolicy}; the default policy is {@link AuthorizationPolicy#strict()}.
 *
 * <p>Contract:
 *
 * <ul>
 *   <li><b>Only narrowing.</b> The returned policy must call the delegate first and return
 *       {@link AuthorizationPolicy.Decision#deny} whenever the delegate denies. It may add further denials for the
 *       tools it knows and must pass the delegate's decision through for every other tool. It must never turn a
 *       denial into an allow, and it must fail closed (deny) when its own check cannot be completed.
 *   <li><b>Ordering.</b> Decorators are applied in Spring order ({@code @Order} on the bean method, lower first;
 *       unordered last) starting from the strict policy, so the first decorator is the innermost and the last one
 *       is outermost (evaluated first).
 *   <li><b>Failure.</b> An exception while building the policy aborts startup.
 * </ul>
 */
@FunctionalInterface
public interface AuthorizationPolicyDecorator {

    AuthorizationPolicy decorate(AuthorizationPolicy delegate);
}
