package net.velocity.agent.tools;

import java.util.List;
import java.util.Map;

import net.velocity.agent.ToolRegistry;
import net.velocity.agent.config.DelegationProperties;
import net.velocity.agent.delegation.DelegationProfileRegistry;

/** A trusted runtime primitive: its marker is handled by ConversationRunner inside the root aggregate. */
public final class DelegateWorkTool {

    private DelegateWorkTool() {
    }

    public static ToolRegistry.Definition definition(
            DelegationProperties properties,
            DelegationProfileRegistry profiles) {
        Map<String, Object> reference = Map.of(
                "type",
                "object",
                "properties",
                Map.of(
                        "kind",
                        Map.of(
                                "type",
                                "string",
                                "enum",
                                List.of("document", "evidence")),
                        "content_hash",
                        text(128),
                        "id",
                        text(128),
                        "reference",
                        text(128)),
                "required",
                List.of("kind", "id", "content_hash"),
                "additionalProperties",
                false);
        return new ToolRegistry.Definition(
                "delegate_work",
                profiles.toolDescription(),
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of(
                                "profile",
                                Map.of("type", "string", "enum", profiles.names()),
                                "objective",
                                text(properties.maxObjectiveChars()),
                                "context",
                                text(properties.maxContextChars()),
                                "references",
                                Map.of(
                                        "type",
                                        "array",
                                        "items",
                                        reference,
                                        "maxItems",
                                        properties.maxReferences())),
                        "required",
                        List.of("profile", "objective"),
                        "additionalProperties",
                        false),
                Map.of("type", "object"),
                "agent.delegation",
                true,
                true,
                false,
                0,
                ToolRegistry.MonitoringAccess.denied,
                new Handler());
    }

    private static Map<String, Object> text(int maximum) {
        return Map.of("type", "string", "minLength", 1, "maxLength", maximum);
    }

    /** Matching a tool name or category alone must never activate privileged child admission. */
    public static final class Handler implements ToolRegistry.Handler {

        private Handler() {
        }

        @Override
        public Object execute(ToolRegistry.Context context, Map<String, Object> input) {
            throw new IllegalStateException("Delegation requires the durable conversation runner");
        }
    }
}
