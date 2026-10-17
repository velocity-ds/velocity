/**
 * The contract a feature or platform module implements to plug into the agent kernel. Core collects beans of
 * these types in Spring {@code @Order} order and never references a feature class; see
 * {@code docs/agent-gateway.md} (wiring contributions) and {@code docs/adding-a-feature.md}. Runtime internals
 * stay in {@code net.velocity.agent} and its subpackages and are not part of this contract.
 */
package net.velocity.agent.spi;
