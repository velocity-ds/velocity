package net.velocity.agent.config;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-call approval settings. Defaults live only in {@code application.properties}. The lists are comma
 * separated; blank entries are ignored and tool names must match the registry's name pattern.
 */
@ConfigurationProperties("agent.approval")
public record ApprovalProperties(boolean enabled, String categories, String tools, String exemptTools) {

    public ApprovalProperties {
        categories = categories == null ? "" : categories;
        tools = tools == null ? "" : tools;
        exemptTools = exemptTools == null ? "" : exemptTools;
        for (String name : names(tools)) {
            requireToolName(name, "agent.approval.tools");
        }
        for (String name : names(exemptTools)) {
            requireToolName(name, "agent.approval.exempt-tools");
        }
        for (String category : names(categories)) {
            if (!category.matches("[a-z]+\\.[a-z_]+")) {
                throw new IllegalArgumentException(
                        "agent.approval.categories entries must look like network.write: " + category);
            }
        }
    }

    public Set<String> categorySet() {
        return names(categories);
    }

    public Set<String> toolSet() {
        return names(tools);
    }

    public Set<String> exemptToolSet() {
        return names(exemptTools);
    }

    private static Set<String> names(String value) {
        var names = new LinkedHashSet<String>();
        Arrays.stream(value.split(",")).map(String::strip).filter(name -> !name.isEmpty())
                .forEach(names::add);
        return Set.copyOf(names);
    }

    private static void requireToolName(String name, String property) {
        if (!name.matches("[a-zA-Z0-9_-]{1,64}")) {
            throw new IllegalArgumentException(property + " contains an invalid tool name: " + name);
        }
    }
}
