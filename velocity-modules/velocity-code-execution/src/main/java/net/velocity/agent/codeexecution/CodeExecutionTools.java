package net.velocity.agent.codeexecution;

import java.util.Map;
import java.util.Optional;

import net.velocity.agent.ToolRegistry;

import static net.velocity.agent.tools.ToolSchemas.*;

public final class CodeExecutionTools {

    private CodeExecutionTools() {
    }

    public static ToolRegistry.Definition definition(CodeExecutionService service) {
        var handler = new Handler(service);
        var definition = new ToolRegistry.Definition(
                "code_run",
                CodeExecutionPrompts.CODE_RUN_DESCRIPTION,
                inputSchema(),
                outputSchema(),
                "system.execute",
                true,
                true,
                false,
                0,
                ToolRegistry.MonitoringAccess.denied,
                handler);
        handler.definition = definition;
        return definition;
    }

    public static Map<String, Object> inputSchema() {
        return object(
                Map.of(
                        "code",
                        string(65_536),
                        "language",
                        enumeration("python"),
                        "input_artifacts",
                        array(
                                object(
                                        Map.of("artifact_id", string(256), "filename", string(100)),
                                        "artifact_id",
                                        "filename"),
                                10),
                        "output_files",
                        array(string(100), 10)),
                "code");
    }

    public static Map<String, Object> outputSchema() {
        return object(
                Map.ofEntries(
                        Map.entry("run_id", text(64)),
                        Map.entry("status", string(64)),
                        Map.entry("exit_code", integer(-2147483648L, 2147483647L)),
                        Map.entry("stdout", text(65_536)),
                        Map.entry("stderr", text(65_536)),
                        Map.entry("stdout_truncated", bool()),
                        Map.entry("stderr_truncated", bool()),
                        Map.entry("duration_ms", integer(0, Long.MAX_VALUE)),
                        Map.entry("failure_code", string(64)),
                        Map.entry("content_trust", enumeration("untrusted_data")),
                        Map.entry(
                                "output_artifacts",
                                array(
                                        object(
                                                Map.of(
                                                        "artifact_id",
                                                        string(256),
                                                        "filename",
                                                        string(100),
                                                        "media_type",
                                                        string(128),
                                                        "byte_size",
                                                        integer(0, SandboxClient.MAX_FILE_BYTES),
                                                        "checksum",
                                                        string(64),
                                                        "created_at",
                                                        string(64)),
                                                "artifact_id",
                                                "filename",
                                                "media_type",
                                                "byte_size",
                                                "checksum",
                                                "created_at"),
                                        10))),
                "run_id",
                "status",
                "stdout",
                "stderr",
                "stdout_truncated",
                "stderr_truncated",
                "duration_ms",
                "output_artifacts",
                "content_trust");
    }

    private static final class Handler
            implements
                ToolRegistry.FailureAwareHandler,
                ToolRegistry.ReconcilingHandler {

        private final CodeExecutionService service;
        private ToolRegistry.Definition definition;

        private Handler(CodeExecutionService service) {
            this.service = service;
        }

        @Override
        public Object execute(ToolRegistry.Context context, Map<String, Object> input) {
            return service.execute(context, input, definition);
        }

        @Override
        public Optional<Object> reconcile(ToolRegistry.Context context, Map<String, Object> input) {
            return service.reconcile(context, input);
        }

        @Override
        public boolean failed(Object output) {
            return !(output instanceof Map<?, ?> result) || !"succeeded".equals(result.get("status"));
        }

    }
}
