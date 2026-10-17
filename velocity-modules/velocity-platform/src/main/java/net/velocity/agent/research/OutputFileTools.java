package net.velocity.agent.research;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.velocity.agent.JsonSchema;
import net.velocity.agent.ToolRegistry;
import net.velocity.agent.persistence.ConflictException;
import net.velocity.agent.persistence.ExecutionRepository;
import net.velocity.agent.spi.OutputFileSource;
import net.velocity.agent.tools.ToolSchemas;
import net.velocity.inference.SystemPrompts;

/**
 * Saves a file the task produced and stages it for delivery with the final answer. The bytes come from model
 * text or from an owned artifact copy; staging reuses the report receipt rule and performs no external write.
 */
public final class OutputFileTools {

    private static final Map<String, Object> INPUT = ToolSchemas.object(
            Map.of(
                    "filename",
                    ToolSchemas.string(100),
                    "content",
                    Map.of("type", "string", "minLength", 1, "maxLength", 2_000_000),
                    "source_id",
                    ToolSchemas.string(160),
                    "description",
                    ToolSchemas.text(300)),
            "filename");

    private static final Map<String, Object> OUTPUT = ToolSchemas.object(
            Map.of(
                    "status",
                    ToolSchemas.enumeration("staged", "not_found", "superseded", "rejected"),
                    "file_id",
                    ToolSchemas.text(160),
                    "filename",
                    ToolSchemas.text(100),
                    "media_type",
                    ToolSchemas.text(150),
                    "byte_size",
                    ToolSchemas.integer(0, Long.MAX_VALUE),
                    "goal_generation",
                    ToolSchemas.integer(1, Long.MAX_VALUE),
                    "current_selection",
                    ToolSchemas.bool(),
                    "message",
                    ToolSchemas.text(1000)),
            "status");

    private OutputFileTools() {
    }

    public static ToolRegistry register(
            ToolRegistry registry,
            ExecutionRepository executions,
            ResearchService research,
            List<OutputFileSource> sources) {
        List<OutputFileSource> featureSources = List.copyOf(sources);
        return registry.register(
                new ToolRegistry.Definition(
                        "task_attach_file",
                        SystemPrompts.TASK_ATTACH_FILE_DESCRIPTION,
                        INPUT,
                        OUTPUT,
                        "repository.write",
                        true,
                        true,
                        false,
                        30_000,
                        ToolRegistry.MonitoringAccess.denied,
                        (context, input) -> attach(executions, research, featureSources, context, input)));
    }

    static Map<String, Object> attach(
            ExecutionRepository executions,
            ResearchService research,
            List<OutputFileSource> sources,
            ToolRegistry.Context context,
            Map<String, Object> input) {
        JsonSchema.validate(INPUT, input);
        boolean inline = input.containsKey("content");
        if (inline == input.containsKey("source_id")) {
            return rejected("Supply exactly one of content or source_id");
        }
        String filename = (String) input.get("filename");
        String description = input.get("description") instanceof String value ? value : "";
        byte[] bytes;
        Map<String, String> provenance = new LinkedHashMap<>();
        if (inline) {
            bytes = ((String) input.get("content")).getBytes(StandardCharsets.UTF_8);
            provenance.put("source", "inline_content");
        } else {
            String sourceId = (String) input.get("source_id");
            Optional<OutputFileSource.SourceFile> file;
            try {
                file = research.ownedSourceFile(context, sourceId);
                for (OutputFileSource source : sources) {
                    if (file.isPresent()) {
                        break;
                    }
                    file = source.find(context, sourceId);
                }
            } catch (ResearchService.ResearchException invalid) {
                return rejected(invalid.getMessage());
            } catch (IllegalArgumentException invalid) {
                return rejected(invalid.getMessage());
            }
            if (file.isEmpty()) {
                return Map.of("status", "not_found", "message", "No file of this task matches source_id");
            }
            bytes = file.get().bytes();
            provenance.put("source", "copied_artifact");
            provenance.put("source_id", sourceId);
            provenance.put("source_media_type", file.get().mediaType());
        }
        try {
            ResearchArtifact saved = research
                    .saveOutputFile(context, filename, bytes, description, provenance);
            return staged(executions, research, context, saved);
        } catch (ConflictException superseded) {
            return Map.of("status", "superseded", "message", "A task correction superseded this file");
        } catch (ResearchService.ResearchException failed) {
            if ("stale_goal".equals(failed.status())) {
                return Map.of("status", "superseded", "message", failed.getMessage());
            }
            return rejected(failed.getMessage());
        } catch (IllegalArgumentException rejected) {
            return rejected(rejected.getMessage());
        }
    }

    /** Stages a saved output file through the shared report receipt rule and reports the staged selection. */
    public static Map<String, Object> staged(
            ExecutionRepository executions,
            ResearchService research,
            ToolRegistry.Context context,
            ResearchArtifact saved) {
        Object outcome = ReportTools.stage(executions, research, context, saved.id());
        if (!(outcome instanceof Map<?, ?> stage) || !"staged".equals(stage.get("status"))) {
            return Map.of("status", "not_found", "message", "The saved file could not be staged");
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("status", "staged");
        result.put("file_id", saved.id());
        result.put("filename", saved.filename());
        result.put("media_type", saved.mediaType());
        result.put("byte_size", saved.byteSize());
        result.put("goal_generation", stage.get("goal_generation"));
        result.put("current_selection", stage.get("current_selection"));
        return result;
    }

    private static Map<String, Object> rejected(String message) {
        return Map.of("status", "rejected", "message", bounded(message));
    }

    private static String bounded(String message) {
        String text = message == null ? "Invalid request" : message;
        return text.substring(0, Math.min(text.length(), 1000));
    }
}
