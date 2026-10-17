package net.velocity.agent.codeexecution;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.velocity.agent.CallProvenance;
import net.velocity.agent.ToolRegistry;
import net.velocity.agent.accounts.AccountRepository;
import net.velocity.agent.persistence.ExecutionRepository;
import net.velocity.agent.persistence.NotFoundException;
import net.velocity.agent.research.ResearchRepository;
import net.velocity.agent.spi.OutputFileSource;
import net.velocity.agent.state.ExecutionStatus;

/** Explicit artifact adapters never widen source ownership or current-task access. */
public final class CodeArtifactService {

    public static final int MAX_INPUT_BYTES = 10 * 1024 * 1024;
    private final CodeArtifactRepository artifacts;
    private final ResearchRepository research;
    private final ExecutionRepository executions;
    private final AccountRepository accounts;
    private final Clock clock;

    public CodeArtifactService(
            CodeArtifactRepository artifacts,
            ResearchRepository research,
            ExecutionRepository executions,
            AccountRepository accounts,
            Clock clock) {
        this.artifacts = artifacts;
        this.research = research;
        this.executions = executions;
        this.accounts = accounts;
        this.clock = clock;
    }

    public record ResolvedInput(String artifactId, String filename, byte[] bytes, String checksum) {

        public ResolvedInput {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        public CodeRunRepository.Input provenance() {
            return new CodeRunRepository.Input(artifactId, filename, checksum, bytes.length);
        }
    }

    public ResolvedInput resolve(ToolRegistry.Context context, String artifactId, String filename) {
        CodeFiles.filename(filename);
        if (artifactId == null || !artifactId.matches("[A-Za-z0-9_.-]{1,160}")) {
            throw new NotFoundException("Input artifact not found");
        }
        accounts.verify(context.scope());
        CallProvenance.current(executions, context);
        remaining(context);
        byte[] bytes;
        var code = artifacts.find(artifactId);
        if (code.isPresent()) {
            var metadata = code.get();
            if (!metadata.ownerAccountId().equals(context.scope().requireOwnerAccountId())
                    || !metadata.executionId().equals(context.executionId())) {
                throw new NotFoundException("Input artifact not found");
            }
            bounded(metadata.byteSize());
            bytes = artifacts.bytes(artifactId);
            if (!CodeFiles.hash(bytes).equals(metadata.checksum())) {
                throw new IllegalStateException("Input artifact checksum mismatch");
            }
        } else if (artifactId.matches("[A-Za-z0-9_-]{1,128}\\.txt")) {
            // ExecutionRepository already scopes its text artifact name to this exact execution.
            StringBuilder contents = new StringBuilder();
            for (long offset = 0;; offset += 65536) {
                context.cancellation().check();
                remaining(context);
                String part = executions.artifact(context.executionId(), artifactId, offset, 65536);
                if ((long) contents.length() + part.length() > MAX_INPUT_BYTES) {
                    throw new IllegalArgumentException("Input artifact exceeds byte limit");
                }
                contents.append(part);
                if (part.length() < 65536) {
                    break;
                }
            }
            bytes = contents.toString().getBytes(StandardCharsets.UTF_8);
        } else {
            var metadata = research
                    .find(artifactId).filter(
                            value -> value.executionId().equals(context.executionId())
                                    && value.scope().sameOwner(context.scope())
                                    && List.of("document", "evidence", "report", "upload")
                                            .contains(value.kind()))
                    .orElseThrow(() -> new NotFoundException("Input artifact not found"));
            bounded(metadata.byteSize());
            bytes = research.bytes(artifactId);
            if (bytes.length != metadata.byteSize() || !CodeFiles.hash(bytes).equals(metadata.checksum())) {
                throw new IllegalStateException("Input artifact checksum or size mismatch");
            }
        }
        bounded(bytes.length);
        remaining(context);
        accounts.verify(context.scope());
        CallProvenance.current(executions, context);
        return new ResolvedInput(artifactId, filename, bytes, CodeFiles.hash(bytes));
    }

    /** Caller holds the owning execution's publication lock through this call and terminal receipt. */
    public CodeArtifact publish(CodeRunRepository.Run run, String filename, byte[] bytes) {
        CodeFiles.filename(filename);
        if (!run.outputFiles().contains(filename)) {
            throw new IllegalArgumentException("Code output was not requested");
        }
        publicationAllowed(run);
        String id = "ca_" + CodeFiles.hash((run.runId() + "\0" + filename).getBytes(StandardCharsets.UTF_8));
        String checksum = CodeFiles.hash(bytes);
        String mediaType = CodeFiles.mediaType(filename, bytes);
        var existing = artifacts.find(id);
        CodeArtifact candidate = new CodeArtifact(
                id,
                run.ownerAccountId(),
                run.executionId(),
                run.runId(),
                run.acceptedCallId(),
                run.acceptedCallBinding(),
                run.goalGeneration(),
                filename,
                mediaType,
                bytes.length,
                checksum,
                existing.map(CodeArtifact::createdAt).orElseGet(clock::instant));
        CodeArtifact saved = artifacts.save(candidate, bytes);
        publicationAllowed(run);
        return saved;
    }

    /**
     * An owned code output artifact copied for delivery with the task's answer; empty for identifiers that
     * are not this task's code artifacts. The copy carries the stored filename and media type.
     */
    public Optional<OutputFileSource.SourceFile> outputFile(ToolRegistry.Context context, String artifactId) {
        if (artifactId == null || !artifactId.matches("ca_[a-f0-9]{64}")) {
            return Optional.empty();
        }
        var found = artifacts.find(artifactId).filter(
                metadata -> metadata.ownerAccountId().equals(context.scope().requireOwnerAccountId())
                        && metadata.executionId().equals(context.executionId()));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        var metadata = found.get();
        bounded(metadata.byteSize());
        byte[] bytes = artifacts.bytes(artifactId);
        if (bytes.length != metadata.byteSize() || !CodeFiles.hash(bytes).equals(metadata.checksum())) {
            throw new IllegalStateException("Code artifact checksum or size mismatch");
        }
        return Optional.of(new OutputFileSource.SourceFile(metadata.filename(), metadata.mediaType(), bytes));
    }

    public List<CodeArtifact> listRun(String executionId, String runId) {
        return artifacts.listExecution(executionId, 10000).stream()
                .filter(value -> value.runId().equals(runId)).toList();
    }

    public void deleteExecution(String executionId) {
        artifacts.deleteExecution(executionId);
    }

    public static Map<String, Object> metadata(CodeArtifact artifact) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("artifact_id", artifact.artifactId());
        result.put("filename", artifact.filename());
        result.put("media_type", artifact.mediaType());
        result.put("byte_size", artifact.byteSize());
        result.put("checksum", artifact.checksum());
        result.put("created_at", artifact.createdAt().toString());
        return Map.copyOf(result);
    }

    private void publicationAllowed(CodeRunRepository.Run run) {
        var state = executions.get(run.executionId());
        if (!state.scope.requireOwnerAccountId().equals(run.ownerAccountId())) {
            throw new NotFoundException("Owned execution not found");
        }
        accounts.verify(state.scope);
        if (state.cancellationRequested) {
            throw new java.util.concurrent.CancellationException();
        }
        if (state.status != ExecutionStatus.running || state.goalGeneration != run.goalGeneration()
                || state.calls.stream().noneMatch(
                        call -> call.id.equals(run.acceptedCallId())
                                && call.binding.equals(run.acceptedCallBinding())
                                && call.goalGeneration == run.goalGeneration())
                || executions.inbox(run.executionId()).stream()
                        .anyMatch(value -> !state.consumedInboxIds.contains(value.id()))) {
            throw new IllegalStateException("A task correction superseded this code output");
        }
    }

    private static void bounded(long size) {
        if (size < 0 || size > MAX_INPUT_BYTES) {
            throw new IllegalArgumentException("Input artifact exceeds byte limit");
        }
    }

    private static void remaining(ToolRegistry.Context context) {
        if (context.remainingOperationMillis() == 0 || Thread.currentThread().isInterrupted()) {
            throw new java.util.concurrent.CancellationException("Code input staging deadline elapsed");
        }
    }
}
