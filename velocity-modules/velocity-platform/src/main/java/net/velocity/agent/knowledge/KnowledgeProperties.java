package net.velocity.agent.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Whether the write tools pause for the requester's confirmation with a preview of the exact change. */
@ConfigurationProperties("agent.knowledge")
public record KnowledgeProperties(@DefaultValue("true") boolean confirmWrites) {}
