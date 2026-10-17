package net.velocity.agent.research;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.velocity.agent.JsonSchema;
import net.velocity.agent.ToolRegistry;
import net.velocity.agent.tools.ToolSchemas;
import net.velocity.inference.SystemPrompts;

import static net.velocity.agent.tools.ToolSchemas.*;

/** All evidence/report operations are scoped and monitoring-denied, with bounded schema envelopes. */
public final class ResearchTools {

    private ResearchTools() {
    }

    public static ToolRegistry register(ToolRegistry registry, ResearchService service) {
        registry.register(
                definition(
                        "task_file_list",
                        SystemPrompts.TASK_FILE_LIST_DESCRIPTION,
                        object(Map.of(), List.of()),
                        "repository.read",
                        15000,
                        service::listFiles));
        registry.register(
                definition(
                        "task_file_open",
                        SystemPrompts.TASK_FILE_OPEN_DESCRIPTION,
                        object(Map.of("file_id", string(80)), List.of("file_id")),
                        "repository.write",
                        120000,
                        service::openFile));
        registry.register(
                definition(
                        "document_read",
                        SystemPrompts.DOCUMENT_READ_DESCRIPTION,
                        object(
                                Map.of(
                                        "document_id",
                                        string(80),
                                        "unit",
                                        integer(1, Integer.MAX_VALUE),
                                        "offset",
                                        integer(0, Integer.MAX_VALUE),
                                        "limit",
                                        integer(1, 12000)),
                                List.of("document_id")),
                        "repository.read",
                        15000,
                        service::readDocument));
        Map<String, Object> citation = object(
                Map.of("evidence_id", string(80), "reference", string(80)),
                List.of("evidence_id"));
        Map<String, Object> section = object(
                Map.of("heading", string(200), "text", string(12000), "citations", array(citation, 50)),
                List.of("heading", "text", "citations"));
        registry.register(
                definition(
                        "report_create",
                        SystemPrompts.REPORT_CREATE_DESCRIPTION,
                        object(
                                Map.of(
                                        "title",
                                        string(200),
                                        "summary",
                                        string(12000),
                                        "sections",
                                        array(section, 30),
                                        "coverage",
                                        string(4000),
                                        "limitations",
                                        array(text(2000), 50),
                                        "next_steps",
                                        array(text(2000), 30)),
                                List.of(
                                        "title",
                                        "summary",
                                        "sections",
                                        "coverage",
                                        "limitations",
                                        "next_steps")),
                        "repository.write",
                        30000,
                        service::createReport));
        return registry;
    }

    private static ToolRegistry.Definition definition(
            String name,
            String description,
            Map<String, Object> input,
            String category,
            long timeout,
            ToolRegistry.Handler handler) {
        return new ToolRegistry.Definition(
                name,
                description,
                input,
                output(name),
                category,
                true,
                true,
                false,
                timeout,
                ToolRegistry.MonitoringAccess.denied,
                (context, value) -> {
                    JsonSchema.validate(input, value);
                    try {
                        return handler.execute(context, value);
                    } catch (ResearchService.ResearchException expected) {
                        return Map.of("status", expected.status(), "message", expected.getMessage());
                    } catch (net.velocity.agent.persistence.NotFoundException missing) {
                        return Map.of("status", "not_found");
                    } catch (net.velocity.agent.security.BrowserRejectedException denied) {
                        return Map.of(
                                "status",
                                "blocked",
                                "message",
                                "Attachment destination is not permitted by the direct-fetch policy");
                    }
                });
    }

    private static Map<String, Object> output(String name) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(
                "status",
                Map.of(
                        "type",
                        "string",
                        "enum",
                        List.of(
                                "ok",
                                "found",
                                "created",
                                "not_found",
                                "invalid_request",
                                "blocked",
                                "unavailable",
                                "partial",
                                "successful",
                                "unsupported",
                                "encrypted",
                                "scanned_requires_ocr",
                                "limit_reached",
                                "stale_goal")));
        fields.put("message", text(1000));
        if (name.equals("task_file_open")) {
            fields.put("document", artifactSchema());
            fields.put("cache_status", string(50));
        } else if (name.equals("task_file_list")) {
            fields.put(
                    "files",
                    array(
                            object(
                                    Map.of(
                                            "file_id",
                                            string(80),
                                            "filename",
                                            string(150),
                                            "media_type",
                                            string(150),
                                            "byte_size",
                                            integer(0, Long.MAX_VALUE),
                                            "document_id",
                                            string(80),
                                            "extraction_status",
                                            string(40)),
                                    List.of("file_id", "filename", "media_type", "byte_size")),
                            100));
            fields.put("content_trust", text(30));
        } else if (name.equals("document_read")) {
            fields.put("document", artifactSchema());
            fields.put("extraction_method", text(100));
            fields.put("content_trust", text(30));
            fields.put("retrieved_at", string(64));
            fields.put("coverage", text(4000));
            fields.put("total_units", integer(0, Integer.MAX_VALUE));
            fields.put("available_units", integer(0, Integer.MAX_VALUE));
            fields.put("unit", integer(1, Integer.MAX_VALUE));
            fields.put("offset", integer(0, Integer.MAX_VALUE));
            fields.put("reference", string(80));
            fields.put("text", text(12000));
            fields.put("complete", bool());
            fields.put("next_unit", integer(-1, Integer.MAX_VALUE));
            fields.put("next_offset", integer(-1, Integer.MAX_VALUE));
        } else {
            fields.put("report", artifactSchema());
            fields.put("receipt_replayed", bool());
        }
        return object(fields, List.of("status"));
    }

    public static Map<String, Object> artifactSchema() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("id", string(80));
        fields.put("kind", string(30));
        fields.put("filename", string(150));
        fields.put("media_type", string(150));
        fields.put("byte_size", integer(0, Long.MAX_VALUE));
        fields.put("checksum", string(64));
        fields.put("created_at", string(64));
        fields.put("goal_generation", integer(1, Long.MAX_VALUE));
        fields.put("evidence_ids", array(string(80), Integer.MAX_VALUE));
        fields.put("coverage", text(4000));
        fields.put("source_url", text(2050));
        fields.put("title", text(300));
        fields.put("status", string(40));
        fields.put("extraction_method", text(100));
        return object(fields, List.copyOf(fields.keySet()));
    }
}
