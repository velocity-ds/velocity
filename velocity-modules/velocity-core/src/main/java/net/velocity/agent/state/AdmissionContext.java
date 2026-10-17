package net.velocity.agent.state;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import net.velocity.agent.JsonSchema;
import net.velocity.agent.ToolRegistry;
import net.velocity.util.Sha256;

/** Exact trusted prompt and serializable tool contracts admitted before the first request. */
public record AdmissionContext(int formatVersion, String instructions, String digest, List<Tool> tools) {

    private static final ObjectWriter CONTRACT_JSON = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build().writer();

    public static final class IncompatibleToolException extends IllegalStateException {

        public IncompatibleToolException(String name) {
            super("admitted_tool_incompatible:" + name);
        }
    }

    public AdmissionContext {
        if (formatVersion != 1 || instructions == null || instructions.length() > 262_144
                || !Objects.equals(digest, digest(instructions)) || tools == null || tools.size() > 256) {
            throw new IllegalArgumentException("Invalid admission context");
        }
        tools = List.copyOf(tools);
        if (tools.stream().map(Tool::name).distinct().count() != tools.size()) {
            throw new IllegalArgumentException("Duplicate admitted tool");
        }
    }

    public static AdmissionContext of(String instructions, List<ToolRegistry.Definition> tools) {
        return new AdmissionContext(
                1,
                instructions,
                digest(instructions),
                tools.stream().map(Tool::of).toList());
    }

    public static String digest(String text) {
        return Sha256.hex(text);
    }

    public record Tool(
            String name,
            String description,
            Map<String, Object> inputSchema,
            Map<String, Object> outputSchema,
            String category,
            boolean trusted,
            boolean scoped,
            boolean cacheable,
            long timeoutMillis,
            ToolRegistry.MonitoringAccess monitoringAccess) {

        public Tool {
            if (name == null || !name.matches("[a-zA-Z0-9_-]{1,64}") || description == null
                    || category == null
                    || monitoringAccess == null || timeoutMillis < 0) {
                throw new IllegalArgumentException("Invalid admitted tool");
            }
            inputSchema = Map.copyOf(inputSchema);
            outputSchema = Map.copyOf(outputSchema);
            JsonSchema.check(inputSchema);
            JsonSchema.check(outputSchema);
        }

        public static Tool of(ToolRegistry.Definition definition) {
            return new Tool(
                    definition.name(),
                    definition.description(),
                    definition.inputSchema(),
                    definition.outputSchema(),
                    definition.category(),
                    definition.trusted(),
                    definition.scoped(),
                    definition.cacheable(),
                    definition.timeoutMillis(),
                    definition.monitoringAccess());
        }

        public boolean compatible(ToolRegistry.Definition definition) {
            try {
                return CONTRACT_JSON.writeValueAsString(this)
                        .equals(CONTRACT_JSON.writeValueAsString(of(definition)));
            } catch (java.io.IOException invalid) {
                return false;
            }
        }

        public ToolRegistry.Definition resolve(ToolRegistry registry) {
            var current = registry.find(name).orElseThrow(() -> new IncompatibleToolException(name));
            if (!compatible(current)) {
                throw new IncompatibleToolException(name);
            }
            return new ToolRegistry.Definition(
                    name,
                    description,
                    inputSchema,
                    outputSchema,
                    category,
                    trusted,
                    scoped,
                    cacheable,
                    timeoutMillis,
                    monitoringAccess,
                    current.monitoringInput(),
                    current.handler());
        }
    }
}
