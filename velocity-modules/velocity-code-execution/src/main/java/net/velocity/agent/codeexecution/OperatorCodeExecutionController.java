package net.velocity.agent.codeexecution;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.type.TypeReference;

import net.velocity.agent.AgentGateway;
import net.velocity.agent.http.LocalAccountScope;
import net.velocity.agent.persistence.NotFoundException;
import net.velocity.agent.state.ExecutionSnapshot;

import jakarta.servlet.http.HttpServletRequest;

/** Registered local-account reads behind the existing local operator transport filter. */
@RestController
public final class OperatorCodeExecutionController {

    private final AgentGateway gateway;
    private final CodeRunRepository runs;
    private final CodeArtifactRepository artifacts;
    private final LocalAccountScope localAccount;

    public OperatorCodeExecutionController(
            AgentGateway gateway,
            CodeRunRepository runs,
            CodeArtifactRepository artifacts,
            LocalAccountScope localAccount) {
        this.gateway = gateway;
        this.runs = runs;
        this.artifacts = artifacts;
        this.localAccount = localAccount;
    }

    public record ArtifactView(
            String artifactId,
            String filename,
            String mediaType,
            long byteSize,
            String checksum,
            Instant createdAt,
            String runId,
            long goalGeneration,
            boolean currentGeneration) {}

    public record RunView(
            String runId,
            String status,
            long goalGeneration,
            boolean currentGeneration,
            Integer exitCode,
            String stdout,
            String stderr,
            boolean stdoutTruncated,
            boolean stderrTruncated,
            Long durationMillis,
            String failureCode,
            boolean cleanupPending,
            List<ArtifactView> outputArtifacts) {}

    @GetMapping("/api/operator/tasks/{id}/code-runs")
    ResponseEntity<List<RunView>> runs(@PathVariable String id, HttpServletRequest request)
            throws IOException {
        empty(request);
        var task = ownedTask(id);
        var files = artifacts.listExecution(id, 1000);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(
                        runs.listExecution(id, 100).stream().filter(
                                run -> run.ownerAccountId().equals(task.scope.requireOwnerAccountId()))
                                .map(run -> view(task, run, files)).toList());
    }

    @GetMapping("/api/operator/tasks/{id}/artifacts")
    ResponseEntity<List<ArtifactView>> artifacts(@PathVariable String id, HttpServletRequest request)
            throws IOException {
        empty(request);
        var task = ownedTask(id);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(
                        artifacts.listExecution(id, 1000).stream().filter(value -> owned(task, value))
                                .map(value -> view(task, value)).toList());
    }

    @GetMapping("/api/operator/tasks/{id}/artifacts/{artifactId}/download")
    ResponseEntity<byte[]> download(
            @PathVariable String id,
            @PathVariable String artifactId,
            HttpServletRequest request) throws IOException {
        empty(request);
        var task = ownedTask(id);
        var artifact = artifacts.find(artifactId).filter(value -> owned(task, value))
                .orElseThrow(() -> new NotFoundException("Code artifact not found"));
        CodeFiles.filename(artifact.filename());
        byte[] bytes = artifacts.bytes(artifactId);
        // All formats download; no active document is rendered in the operator origin.
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(bytes.length)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(artifact.filename()).build().toString())
                .header("X-Content-Type-Options", "nosniff").header("Content-Security-Policy", "sandbox")
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(bytes);
    }

    private RunView view(ExecutionSnapshot task, CodeRunRepository.Run run, List<CodeArtifact> files) {
        Map<String, Object> result = Map.of();
        if (run.receiptJson() != null) {
            try {
                result = MongoCodeRunRepository.JSON
                        .readValue(run.receiptJson(), new TypeReference<Map<String, Object>>() {});
            } catch (IOException invalid) {
                throw new IllegalStateException("Code run receipt is invalid", null);
            }
        }
        List<String> ids = result.get("output_artifacts") instanceof List<?> output
                ? output.stream()
                        .filter(Map.class::isInstance).map(Map.class::cast)
                        .map(value -> value.get("artifact_id"))
                        .filter(String.class::isInstance).map(String.class::cast).toList()
                : List.of();
        List<ArtifactView> manifest = files.stream()
                .filter(
                        value -> owned(task, value) && run.runId().equals(value.runId())
                                && ids.contains(value.artifactId()))
                .map(value -> view(task, value)).toList();
        return new RunView(
                run.runId(),
                text(result, "status", run.state(), 64),
                run.goalGeneration(),
                task.goalGeneration == run.goalGeneration(),
                result.get("exit_code") instanceof Number value ? value.intValue() : null,
                text(result, "stdout", "", 8192),
                text(result, "stderr", "", 8192),
                clipped(result, "stdout"),
                clipped(result, "stderr"),
                result.get("duration_ms") instanceof Number value ? value.longValue() : null,
                text(result, "failure_code", null, 128),
                run.cleanupPending(),
                manifest);
    }

    private static ArtifactView view(ExecutionSnapshot task, CodeArtifact artifact) {
        return new ArtifactView(
                artifact.artifactId(),
                artifact.filename(),
                artifact.mediaType(),
                artifact.byteSize(),
                artifact.checksum(),
                artifact.createdAt(),
                artifact.runId(),
                artifact.goalGeneration(),
                task.goalGeneration == artifact.goalGeneration());
    }

    private static boolean owned(ExecutionSnapshot task, CodeArtifact artifact) {
        return task.executionId.equals(artifact.executionId())
                && task.scope.requireOwnerAccountId().equals(artifact.ownerAccountId());
    }

    private ExecutionSnapshot ownedTask(String id) {
        var scope = localAccount.scope();
        var task = gateway.get(id);
        if (!task.scope.sameOwner(scope)) {
            throw new NotFoundException("Owned execution not found");
        }
        return task;
    }

    private static boolean clipped(Map<String, Object> result, String name) {
        return Boolean.TRUE.equals(result.get(name + "_truncated"))
                || result.get(name) instanceof String value && value.length() > 8192;
    }

    private static String text(Map<String, Object> map, String key, String fallback, int limit) {
        if (!(map.get(key) instanceof String value)) {
            return fallback;
        }
        return value.substring(0, Math.min(value.length(), limit));
    }

    private static void empty(HttpServletRequest request) throws IOException {
        if (request.getQueryString() != null || request.getInputStream().read() != -1) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Code artifact reads accept no body or query parameters");
        }
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Map<String, String>> missing() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Code artifact or execution not found"));
    }
}
