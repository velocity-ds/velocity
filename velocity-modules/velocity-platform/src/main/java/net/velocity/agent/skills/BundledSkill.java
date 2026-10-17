package net.velocity.agent.skills;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One original, inert Velocity workflow that the local operator can install into the ordinary account-owned skill store.
 * Released versions are immutable: changing the content of a released version requires a new version and receipt
 * identity, because installation is receipted per account, bundle id and version.
 *
 * @param id stable identifier, {@code [a-z][a-z0-9_-]{0,31}}
 * @param version positive release number
 */
public record BundledSkill(
        String id,
        int version,
        String name,
        String routing,
        String core,
        String referenceName,
        String reference) {

    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_-]{0,31}");

    public BundledSkill {
        if (id == null || !ID.matcher(id).matches() || version < 1) {
            throw new IllegalArgumentException("Invalid bundled skill identity: " + id);
        }
        for (String text : new String[]{name, routing, core, referenceName, reference}) {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("Bundled skill text is required: " + id);
            }
        }
    }

    List<Map<String, Object>> references() {
        return List.of(Map.of("id", "workflow-example", "name", referenceName, "content", reference));
    }

    Map<String, Object> input() {
        return Map.of(
                "name",
                name,
                "routing_description",
                routing,
                "core_instructions",
                core,
                "references",
                references());
    }

    Map<String, Object> preview() {
        return Map.of(
                "id",
                id,
                "version",
                version,
                "name",
                name,
                "routing_description",
                routing,
                "core_instructions",
                core,
                "references",
                references());
    }
}
