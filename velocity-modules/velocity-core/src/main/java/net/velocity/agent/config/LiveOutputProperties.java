package net.velocity.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Live console output settings. Saved task changes are always pushed over server-sent events; model
 * streaming additionally relays assistant text while a model turn is in flight.
 */
@ConfigurationProperties("agent.live-output")
public record LiveOutputProperties(boolean modelStreaming) {}
