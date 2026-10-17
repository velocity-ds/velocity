package net.velocity.agent.codeexecution;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Exact accepted-call intents and receipts. A missing receipt never permits redispatch. */
public interface CodeRunRepository {

    record Input(String artifactId, String filename, String checksum, long byteSize) {

        public Input {
            if (artifactId == null || artifactId.isBlank() || checksum == null
                    || !checksum.matches("[a-f0-9]{64}")
                    || byteSize < 0) {
                throw new IllegalArgumentException("Invalid code input provenance");
            }
            CodeFiles.filename(filename);
        }
    }

    record Run(
            String runId,
            String ownerAccountId,
            String executionId,
            String acceptedCallId,
            String acceptedCallBinding,
            long goalGeneration,
            String runtimeImage,
            String sourceHash,
            List<Input> inputs,
            List<String> outputFiles,
            String requestJson,
            String resourceName,
            String state,
            String receiptJson,
            boolean cleanupPending,
            long revision,
            Instant createdAt,
            Instant updatedAt) {

        public Run {
            if (runId == null || runId.isBlank() || runId.length() > 128 || ownerAccountId == null
                    || !java.util.UUID.fromString(ownerAccountId).toString().equals(ownerAccountId)
                    || executionId == null || executionId.isBlank() || acceptedCallId == null
                    || acceptedCallId.isBlank()
                    || acceptedCallBinding == null || acceptedCallBinding.isBlank() || goalGeneration < 1
                    || runtimeImage == null || runtimeImage.isBlank() || sourceHash == null
                    || !sourceHash.matches("[a-f0-9]{64}")
                    || requestJson == null || requestJson.length() > 524288 || resourceName == null
                    || !resourceName.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}")
                    || !Set.of(
                            "admitted",
                            "creating",
                            "starting",
                            "running",
                            "collecting",
                            "terminal",
                            "uncertain").contains(state)
                    || state.equals("terminal") != (receiptJson != null)
                    || receiptJson != null && receiptJson.length() > 1048576
                    || revision < 1 || createdAt == null || updatedAt == null
                    || updatedAt.isBefore(createdAt)) {
                throw new IllegalArgumentException("Invalid code run journal");
            }
            inputs = List.copyOf(inputs);
            outputFiles = List.copyOf(outputFiles);
            if (inputs.size() > 10 || outputFiles.size() > 10) {
                throw new IllegalArgumentException("Too many code files");
            }
            CodeFiles.unique(outputFiles);
            CodeFiles.unique(inputs.stream().map(Input::filename).toList());
        }

        public Run advance(String state, String receiptJson, boolean cleanupPending, Instant at) {
            return new Run(
                    runId,
                    ownerAccountId,
                    executionId,
                    acceptedCallId,
                    acceptedCallBinding,
                    goalGeneration,
                    runtimeImage,
                    sourceHash,
                    inputs,
                    outputFiles,
                    requestJson,
                    resourceName,
                    state,
                    receiptJson,
                    cleanupPending,
                    Math.addExact(revision, 1),
                    createdAt,
                    at);
        }

        public boolean sameCall(Run other) {
            return runId.equals(other.runId) && ownerAccountId.equals(other.ownerAccountId)
                    && executionId.equals(other.executionId) && acceptedCallId.equals(other.acceptedCallId)
                    && acceptedCallBinding.equals(other.acceptedCallBinding)
                    && goalGeneration == other.goalGeneration
                    && runtimeImage.equals(other.runtimeImage) && sourceHash.equals(other.sourceHash)
                    && inputs.equals(other.inputs) && outputFiles.equals(other.outputFiles)
                    && requestJson.equals(other.requestJson) && resourceName.equals(other.resourceName)
                    && createdAt.equals(other.createdAt);
        }

        public boolean validReplacement(Run replacement) {
            if (!sameCall(replacement) || replacement.revision != revision + 1
                    || replacement.updatedAt.isBefore(updatedAt)) {
                return false;
            }
            if (state.equals("terminal")) {
                return replacement.state.equals("terminal")
                        && Objects.equals(receiptJson, replacement.receiptJson)
                        && (cleanupPending || !replacement.cleanupPending);
            }
            return switch (state) {
                case "admitted" -> Set.of("creating", "terminal", "uncertain").contains(replacement.state);
                case "creating" ->
                    Set.of("starting", "running", "terminal", "uncertain").contains(replacement.state);
                case "starting" ->
                    Set.of("running", "collecting", "terminal", "uncertain").contains(replacement.state);
                case "running" -> Set.of("collecting", "terminal", "uncertain").contains(replacement.state);
                case "collecting", "uncertain" -> Set.of("terminal", "uncertain").contains(replacement.state);
                default -> false;
            };
        }

        @Override
        public String toString() {
            return "CodeRun[" + state + ", REDACTED]";
        }
    }

    Optional<Run> find(String runId);

    boolean insert(Run admitted);

    boolean compareAndSet(Run replacement, long expectedRevision);

    List<Run> listExecution(String executionId, int limit);

    List<Run> recoverable(int limit);

    /** Refuses to erase unresolved runner evidence. Call only after verified worker settlement. */
    void deleteExecution(String executionId);

    boolean deleted(String executionId);
}
