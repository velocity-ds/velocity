package net.velocity.agent.codeexecution;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.velocity.agent.CancellationHandle;
import net.velocity.inference.InferenceDeadlineScope;

/** Fixed Docker argv and a root-owned supervisor protocol; never invokes a host shell. */
public final class DockerSandboxClient implements SandboxClient {

    static final String INSTALLATION_LABEL = "net.velocity.code.installation";
    static final String RUN_LABEL = "net.velocity.code.run";
    static final String PROFILE_LABEL = "net.velocity.code.profile";
    static final String RUNTIME_PROFILE = "velocity-python-v3";
    // Older journaled workers still need inspection/termination after an application upgrade.
    private static final Set<String> RECOVERABLE_PROFILES = Set
            .of("velocity-python-v1", "velocity-python-v2", RUNTIME_PROFILE);
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final int MAX_PROTOCOL = 36 * 1024 * 1024;
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(8);
    private final String executable;
    private final String image;
    private final String installationId;
    private final CommandRunner commands;
    private final ThreadLocal<Boolean> cleanupCommands = new ThreadLocal<>();

    /** Construction is deliberately inert, including when the feature is disabled/unconfigured. */
    public DockerSandboxClient(String executable, String image, String installationId) {
        this(executable, image, installationId, new ProcessCommands());
    }

    DockerSandboxClient(String executable, String image, String installationId, CommandRunner commands) {
        this.executable = executable;
        this.image = image;
        this.installationId = installationId;
        this.commands = commands;
    }

    @Override
    public Availability availability() {
        if (image == null || image.isBlank() || installationId == null || installationId.isBlank()) {
            return new Availability(
                    false,
                    "Install the pinned runtime image and configure the installation identity",
                    PROFILE);
        }
        try {
            validateConfiguration();
            JsonNode info = parse(run(List.of("info", "--format", "{{json .}}"), null, 65536));
            if (!"linux".equals(info.path("OSType").asText())
                    || !"2".equals(info.path("CgroupVersion").asText())
                    || !info.path("MemoryLimit").asBoolean() || !info.path("PidsLimit").asBoolean()
                    || !info.path("CpuCfsPeriod").asBoolean() || !info.path("CpuCfsQuota").asBoolean()
                    || !info.path("SwapLimit").asBoolean()
                    || !info.path("SecurityOptions").toString().contains("name=seccomp,profile=builtin")) {
                return new Availability(
                        false,
                        "Linux cgroup v2, CPU/memory/swap/PID controls and seccomp are required",
                        PROFILE);
            }
            JsonNode runtime = parse(run(List.of("image", "inspect", image), null, 65536)).path(0);
            if (!"linux".equals(runtime.path("Os").asText())
                    || !"amd64".equals(runtime.path("Architecture").asText())
                    || !RUNTIME_PROFILE
                            .equals(runtime.path("Config").path("Labels").path(PROFILE_LABEL).asText())) {
                return new Availability(
                        false,
                        "Install the approved linux/amd64 Python runtime profile",
                        PROFILE);
            }
            return new Availability(true, "", PROFILE);
        } catch (Exception unavailable) {
            return new Availability(
                    false,
                    "Docker or the installed pinned runtime is unavailable; check worker setup",
                    PROFILE);
        }
    }

    @Override
    public void createAndStart(RunSpec spec) throws Exception {
        long deadline = spec.operationDeadlineNanos() == Long.MAX_VALUE
                ? InferenceDeadlineScope.captureDeadline(spec.executionMillis())
                : spec.operationDeadlineNanos();
        try (var ignored = InferenceDeadlineScope.open(deadline)) {
            createWithinDeadline(spec, deadline);
        }
    }

    private void createWithinDeadline(RunSpec spec, long deadline) throws Exception {
        validateConfiguration();
        validateSpec(spec, true);
        if (!spec.runtimeImage().isBlank() && !image.equals(spec.runtimeImage())) {
            throw new IOException("sandbox_runtime_changed_before_dispatch");
        }
        Availability available = availability();
        if (!available.available()) {
            throw new IOException("sandbox_unavailable");
        }
        requireBudget(spec);
        // No --rm: durable result publication must precede deletion of the only remaining evidence.
        run(createArguments(spec), null, 65536);
        JsonNode container = exactContainer(spec);
        if (!RUNTIME_PROFILE.equals(container.path("Config").path("Labels").path(PROFILE_LABEL).asText())) {
            throw new IOException("sandbox_runtime_changed_before_dispatch");
        }
        verifyBoundaries(container);
        requireBudget(spec);
        run(List.of("start", spec.resourceName()), null, 65536);
        // Starting the supervisor is not dispatching user code. Do not retry the run request.
        long readyUntil = deadline;
        boolean ready = false;
        while (System.nanoTime() < readyUntil) {
            try {
                long clockBefore = System.currentTimeMillis();
                JsonNode state = control(spec, Map.of("command", "status"), 65536);
                if ("waiting".equals(state.path("state").asText())) {
                    long workerClock = state.path("epoch_millis").asLong(0);
                    if (workerClock < clockBefore - 250 || workerClock > System.currentTimeMillis() + 250) {
                        throw new IOException("sandbox_clock_not_synchronized");
                    }
                    ready = true;
                    break;
                }
                throw new IOException("sandbox_already_started");
            } catch (IOException waiting) {
                Thread.sleep(25);
            }
        }
        if (!ready) {
            throw new IOException("sandbox_start_unconfirmed");
        }
        List<Map<String, String>> inputs = spec.inputs().stream()
                .map(
                        input -> Map.of(
                                "filename",
                                input.filename(),
                                "data",
                                Base64.getEncoder().encodeToString(input.bytes())))
                .toList();
        var request = new HashMap<String, Object>();
        request.put("command", "run");
        request.put("code", spec.code());
        request.put("inputs", inputs);
        request.put("outputs", spec.outputFiles());
        long executionMillis = Math.min(spec.executionMillis(), requireBudget(spec));
        request.put("execution_millis", executionMillis);
        request.put("deadline_epoch_millis", System.currentTimeMillis() + executionMillis);
        // Deadline includes staging and launch: the supervisor starts this timer before reading inputs.
        JsonNode started = control(spec, request, 65536);
        if (!"running".equals(started.path("state").asText())) {
            throw new IOException("sandbox_dispatch_unconfirmed");
        }
    }

    List<String> createArguments(RunSpec spec) {
        return List.of(
                "create",
                "--pull=never",
                "--name",
                spec.resourceName(),
                "--label",
                INSTALLATION_LABEL + "=" + installationId,
                "--label",
                RUN_LABEL + "=" + spec.runId(),
                "--label",
                PROFILE_LABEL + "=" + RUNTIME_PROFILE,
                "--platform=linux/amd64",
                "--network=none",
                "--ipc=none",
                "--read-only",
                "--user=0:0",
                "--cap-drop=ALL",
                "--cap-add=SETUID",
                "--cap-add=SETGID",
                "--cap-add=KILL",
                "--cap-add=DAC_READ_SEARCH",
                "--security-opt=no-new-privileges=true",
                "--cpus=1",
                "--memory=536870912",
                "--memory-swap=536870912",
                "--pids-limit=64",
                "--ulimit=nofile=256:256",
                "--ulimit=core=0:0",
                "--log-driver=none",
                "--restart=no",
                "--tmpfs=/work:rw,nosuid,nodev,noexec,size=134217728,mode=755",
                "--entrypoint=/usr/local/bin/python3",
                image,
                "-I",
                "-B",
                "/opt/velocity/supervisor.py",
                Long.toString(
                        System.currentTimeMillis()
                                + (spec.operationDeadlineNanos() == Long.MAX_VALUE
                                        ? spec.executionMillis()
                                        : Math.max(
                                                0,
                                                TimeUnit.NANOSECONDS
                                                        .toMillis(
                                                                spec.operationDeadlineNanos()
                                                                        - System.nanoTime())))));
    }

    @Override
    public Result await(RunSpec spec, CancellationHandle cancellation) throws Exception {
        try (var ignored = InferenceDeadlineScope.open(spec.operationDeadlineNanos())) {
            return awaitWithinDeadline(spec, cancellation);
        }
    }

    private Result awaitWithinDeadline(RunSpec spec, CancellationHandle cancellation) throws Exception {
        long end = spec.operationDeadlineNanos();
        while (System.nanoTime() < end) {
            if (cancellation.cancelled() || Thread.interrupted()) {
                return terminate(spec);
            }
            try {
                Result result = inspect(spec);
                if (!"running".equals(result.status())) {
                    return result;
                }
            } catch (IOException transientInspection) {
                // Read-only retry is safe; a full PID cgroup can temporarily prevent docker exec.
                if ("sandbox_identity_conflict".equals(transientInspection.getMessage())) {
                    throw transientInspection;
                }
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                return terminate(spec);
            }
        }
        return terminate(spec);
    }

    @Override
    public Result inspect(RunSpec spec) throws Exception {
        validateSpec(spec, false);
        JsonNode container = exactContainer(spec);
        if (container == null) {
            return absentResult();
        }
        if (!container.path("State").path("Running").asBoolean()) {
            boolean oom = container.path("State").path("OOMKilled").asBoolean();
            return new Result(
                    oom ? "resource_limited" : "uncertain",
                    null,
                    "",
                    "",
                    false,
                    false,
                    0,
                    List.of(),
                    oom ? "memory_limit" : "supervisor_evidence_unavailable",
                    true);
        }
        JsonNode state = control(spec, Map.of("command", "status"), MAX_PROTOCOL);
        if (Set.of("running", "waiting", "cancelling").contains(state.path("state").asText())) {
            return Result.running();
        }
        if (!"terminal".equals(state.path("state").asText())) {
            return Result.uncertain("supervisor_evidence_unavailable");
        }
        return decodeResult(spec, control(spec, Map.of("command", "collect"), MAX_PROTOCOL));
    }

    @Override
    public Result terminate(RunSpec spec) throws Exception {
        Boolean previous = cleanupCommands.get();
        cleanupCommands.set(true);
        try {
            return terminateWithCleanupGrace(spec);
        } finally {
            if (previous == null) {
                cleanupCommands.remove();
            } else {
                cleanupCommands.set(previous);
            }
        }
    }

    private Result terminateWithCleanupGrace(RunSpec spec) throws Exception {
        validateSpec(spec, false);
        // Interrupting a Docker CLI process never proves that a container stopped.
        Thread.interrupted();
        JsonNode container = exactContainer(spec);
        if (container == null) {
            return absentResult();
        }
        if (!container.path("State").path("Running").asBoolean()) {
            return inspect(spec);
        }
        try {
            control(spec, Map.of("command", "cancel"), 65536);
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while (System.nanoTime() < end) {
                Result result = inspect(spec);
                if (result.terminationConfirmed()) {
                    return result;
                }
                Thread.sleep(50);
            }
        } catch (Exception unavailable) {
            Thread.interrupted();
        }
        // A full PID cgroup can prevent docker exec. Docker kill is the trusted fallback.
        if (exactContainer(spec) == null) {
            return absentResult();
        }
        run(List.of("kill", "--signal=KILL", spec.resourceName()), null, 65536);
        container = exactContainer(spec);
        if (container == null) {
            return absentResult();
        }
        if (container.path("State").path("Running").asBoolean()) {
            return Result.uncertain("termination_unconfirmed");
        }
        return new Result(
                "cancelled",
                null,
                "",
                "",
                false,
                false,
                0,
                List.of(),
                "cancelled_output_unavailable",
                true);
    }

    @Override
    public boolean remove(RunSpec spec) throws Exception {
        Boolean previous = cleanupCommands.get();
        cleanupCommands.set(true);
        try {
            return removeWithCleanupGrace(spec);
        } finally {
            if (previous == null) {
                cleanupCommands.remove();
            } else {
                cleanupCommands.set(previous);
            }
        }
    }

    private boolean removeWithCleanupGrace(RunSpec spec) throws Exception {
        validateSpec(spec, false);
        JsonNode container = exactContainer(spec);
        if (container == null) {
            return true;
        }
        if (container.path("State").path("Running").asBoolean()) {
            Result result = terminate(spec);
            if (!result.terminationConfirmed()) {
                return false;
            }
        }
        container = exactContainer(spec);
        if (container == null) {
            return true;
        }
        // Guest settlement deliberately leaves PID 1 alive to preserve tmpfs for collection.
        // The service invokes remove only after the terminal receipt is durable.
        if (container.path("State").path("Running").asBoolean()) {
            try {
                run(List.of("kill", "--signal=KILL", spec.resourceName()), null, 65536);
            } catch (IOException lostResponse) {
                /* Exact state inspection below is authoritative. */ }
            container = exactContainer(spec);
            if (container == null) {
                return true;
            }
            if (container.path("State").path("Running").asBoolean()) {
                return false;
            }
        }
        run(List.of("rm", spec.resourceName()), null, 65536);
        return true;
    }

    Result decodeResult(RunSpec spec, JsonNode node) throws IOException {
        if (!"terminal".equals(node.path("state").asText())
                || !node.path("termination_confirmed").asBoolean()) {
            return Result.uncertain("termination_unconfirmed");
        }
        String status = node.path("status").asText();
        if (!Set.of("succeeded", "failed", "cancelled", "timed_out", "resource_limited", "missing_outputs")
                .contains(status)) {
            throw new IOException("invalid_runner_status");
        }
        String stdout = node.path("stdout").asText();
        String stderr = node.path("stderr").asText();
        if (stdout.length() > MAX_STREAM_BYTES || stderr.length() > MAX_STREAM_BYTES) {
            throw new IOException("runner_stream_limit");
        }
        JsonNode files = node.path("outputs");
        if (!files.isArray() || files.size() > MAX_OUTPUT_FILES) {
            throw new IOException("runner_file_count_limit");
        }
        Set<String> seen = new HashSet<>();
        List<OutputFile> outputs = new ArrayList<>();
        int total = 0;
        String failure = node.path("failure_code").isTextual() ? node.path("failure_code").asText() : null;
        for (JsonNode file : files) {
            String name = CodeFiles.filename(file.path("filename").asText());
            if (!spec.outputFiles().contains(name) || !seen.add(name) || !file.path("data").isTextual()
                    || file.path("data").asText().length() > ((MAX_FILE_BYTES + 2) / 3) * 4) {
                throw new IOException("invalid_runner_output");
            }
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(file.path("data").asText());
            } catch (IllegalArgumentException invalid) {
                throw new IOException("invalid_runner_output");
            }
            total += bytes.length;
            if (bytes.length > MAX_FILE_BYTES || total > MAX_OUTPUT_BYTES) {
                throw new IOException("runner_output_limit");
            }
            try {
                outputs.add(
                        new OutputFile(name, bytes, CodeFiles.mediaType(name, bytes), CodeFiles.hash(bytes)));
            } catch (IllegalArgumentException invalidFormat) {
                status = "succeeded".equals(status) ? "failed" : status;
                failure = "invalid_output_format";
            }
        }
        if (seen.size() != spec.outputFiles().size() && "succeeded".equals(status)) {
            status = "missing_outputs";
            failure = "missing_outputs";
        }
        return new Result(
                status,
                node.path("exit_code").isIntegralNumber() ? node.path("exit_code").intValue() : null,
                stdout,
                stderr,
                node.path("stdout_truncated").asBoolean(),
                node.path("stderr_truncated").asBoolean(),
                Math.max(0, node.path("duration_ms").asLong()),
                outputs,
                failure,
                true);
    }

    private JsonNode exactContainer(RunSpec spec) throws Exception {
        CommandResult response = execute(List.of("container", "inspect", spec.resourceName()), null, 65536);
        if (response.truncated()) {
            throw new IOException("docker_response_limit");
        }
        if (response.exitCode() != 0) {
            // A failed inspect can mean a backend outage. A successful exact-name listing is required.
            byte[] listing = run(
                    List.of(
                            "container",
                            "ls",
                            "--all",
                            "--filter",
                            "name=" + spec.resourceName(),
                            "--format",
                            "{{json .Names}}"),
                    null,
                    65536);
            for (String line : new String(listing, StandardCharsets.UTF_8).lines().toList()) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode name = parse(line.getBytes(StandardCharsets.UTF_8));
                if (!name.isTextual()) {
                    throw new IOException("invalid_docker_listing");
                }
                if (spec.resourceName().equals(name.asText())) {
                    throw new IOException("sandbox_inspection_unavailable");
                }
            }
            return null;
        }
        JsonNode container = parse(response.stdout()).path(0);
        validateLabels(spec, container);
        return container;
    }

    private Result absentResult() {
        return new Result(
                "uncertain",
                null,
                "",
                "",
                false,
                false,
                0,
                List.of(),
                "sandbox_absent_outcome_unavailable",
                true);
    }

    private void validateLabels(RunSpec spec, JsonNode container) throws IOException {
        JsonNode labels = container.path("Config").path("Labels");
        if (!installationId.equals(labels.path(INSTALLATION_LABEL).asText())
                || !spec.runId().equals(labels.path(RUN_LABEL).asText())
                || !RECOVERABLE_PROFILES.contains(labels.path(PROFILE_LABEL).asText())
                || !(spec.runtimeImage().isBlank() ? image : spec.runtimeImage())
                        .equals(container.path("Config").path("Image").asText())
                || !("/" + spec.resourceName()).equals(container.path("Name").asText())) {
            throw new IOException("sandbox_identity_conflict");
        }
    }

    private void verifyBoundaries(JsonNode container) throws IOException {
        JsonNode host = container.path("HostConfig");
        if (!host.path("ReadonlyRootfs").asBoolean() || host.path("Privileged").asBoolean()
                || !"none".equals(host.path("NetworkMode").asText())
                || !"none".equals(host.path("IpcMode").asText())
                || !host.path("PidMode").asText().isEmpty()
                || !host.path("Binds").isNull() && host.path("Binds").size() > 0
                || host.path("Memory").asLong() != 536870912L
                || host.path("MemorySwap").asLong() != 536870912L
                || host.path("NanoCpus").asLong() != 1000000000L || host.path("PidsLimit").asLong() != 64
                || !capabilities(host.path("CapDrop")).equals(Set.of("ALL"))
                || !capabilities(host.path("CapAdd"))
                        .equals(Set.of("SETUID", "SETGID", "KILL", "DAC_READ_SEARCH"))
                || !"0:0".equals(container.path("Config").path("User").asText())
                || !securityOptions(host.path("SecurityOpt"))
                || !"none".equals(host.path("LogConfig").path("Type").asText())
                || host.path("Tmpfs").size() != 1
                || !Set.of(host.path("Tmpfs").path("/work").asText().split(","))
                        .equals(Set.of("rw", "nosuid", "nodev", "noexec", "size=134217728", "mode=755"))
                || host.path("Devices").size() != 0 || host.path("DeviceRequests").size() != 0
                || !"no".equals(host.path("RestartPolicy").path("Name").asText())
                || !safeMounts(container.path("Mounts"))) {
            throw new IOException("required_sandbox_boundary_unavailable");
        }
    }

    private Set<String> capabilities(JsonNode values) {
        Set<String> result = new HashSet<>();
        for (JsonNode value : values) {
            result.add(value.asText().replaceFirst("^CAP_", ""));
        }
        return result;
    }

    private boolean securityOptions(JsonNode values) {
        if (values.size() != 1) {
            return false;
        }
        String value = values.path(0).asText();
        return "no-new-privileges=true".equals(value) || "no-new-privileges".equals(value);
    }

    private boolean safeMounts(JsonNode mounts) {
        for (JsonNode mount : mounts) {
            if (!"tmpfs".equals(mount.path("Type").asText())
                    || !"/work".equals(mount.path("Destination").asText())) {
                return false;
            }
        }
        return mounts.size() <= 1;
    }

    private JsonNode control(RunSpec spec, Map<String, ?> request, int limit) throws Exception {
        byte[] input = JSON.writeValueAsBytes(request);
        if (input.length > MAX_PROTOCOL) {
            throw new IOException("runner_input_limit");
        }
        JsonNode result = parse(
                run(
                        List.of(
                                "exec",
                                "--user",
                                "0:0",
                                "-i",
                                spec.resourceName(),
                                "/usr/local/bin/python3",
                                "-I",
                                "-B",
                                "/opt/velocity/control.py"),
                        input,
                        limit));
        if (result.has("error")) {
            throw new IOException("runner_protocol_error");
        }
        return result;
    }

    private void validateConfiguration() {
        if (image == null || !image.matches("(?:[a-zA-Z0-9./:_-]+@)?sha256:[a-f0-9]{64}")
                || installationId == null || !installationId.matches("[a-z0-9][a-z0-9-]{7,63}")) {
            throw new IllegalArgumentException("sandbox_unconfigured");
        }
    }

    private void validateSpec(RunSpec spec, boolean dispatch) {
        if (!spec.resourceName().matches("[a-z0-9][a-z0-9_.-]{0,127}")
                || !spec.runId().matches("[a-zA-Z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("invalid_sandbox_identity");
        }
        if (!dispatch) {
            return;
        }
        if (spec.code() == null || spec.code().isBlank()
                || spec.code().getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES
                || spec.executionMillis() < 1
                || spec.inputs().size() > MAX_INPUT_FILES || spec.outputFiles().size() > MAX_OUTPUT_FILES) {
            throw new IllegalArgumentException("invalid_sandbox_limits");
        }
        CodeFiles.unique(spec.inputs().stream().map(InputFile::filename).toList());
        CodeFiles.unique(spec.outputFiles());
        long inputSize = 0;
        for (InputFile input : spec.inputs()) {
            inputSize += input.bytes().length;
            if (input.bytes().length > MAX_FILE_BYTES || inputSize > MAX_INPUT_BYTES) {
                throw new IllegalArgumentException("sandbox_input_limit");
            }
        }
    }

    private long requireBudget(RunSpec spec) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("sandbox_cancelled_before_dispatch");
        }
        long remaining = spec.operationDeadlineNanos() == Long.MAX_VALUE
                ? spec.executionMillis()
                : TimeUnit.NANOSECONDS.toMillis(spec.operationDeadlineNanos() - System.nanoTime());
        remaining = Math.min(
                remaining,
                InferenceDeadlineScope.remaining()
                        .map(Duration::toMillis).orElse(remaining));
        if (remaining < 1) {
            throw new IOException("sandbox_deadline_before_dispatch");
        }
        return remaining;
    }

    private CommandResult execute(List<String> arguments, byte[] input, int limit) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(arguments);
        Duration timeout = Boolean.TRUE.equals(cleanupCommands.get())
                ? COMMAND_TIMEOUT
                : InferenceDeadlineScope.remainingOr(COMMAND_TIMEOUT);
        return commands.run(List.copyOf(command), input, limit, timeout);
    }

    private byte[] run(List<String> arguments, byte[] input, int limit) throws Exception {
        CommandResult result = execute(arguments, input, limit);
        if (result.exitCode() != 0 || result.truncated()) {
            throw new IOException("docker_operation_unavailable");
        }
        return result.stdout();
    }

    private static JsonNode parse(byte[] value) throws IOException {
        return parse(value, false);
    }

    private static JsonNode parse(byte[] value, boolean emptyAllowed) throws IOException {
        if (emptyAllowed && new String(value, StandardCharsets.UTF_8).isBlank()) {
            return JSON.nullNode();
        }
        JsonNode result = JSON.readTree(value);
        if (result == null) {
            throw new IOException("empty_docker_response");
        }
        return result;
    }

    interface CommandRunner {

        CommandResult run(List<String> command, byte[] input, int outputLimit, Duration timeout)
                throws Exception;
    }

    record CommandResult(int exitCode, byte[] stdout, boolean truncated) {}

    static final class ProcessCommands implements CommandRunner {

        @Override
        public CommandResult run(List<String> command, byte[] input, int outputLimit, Duration timeout)
                throws Exception {
            long deadline = System.nanoTime() + Math.min(timeout.toNanos(), Long.MAX_VALUE / 2);
            ProcessBuilder builder = new ProcessBuilder(command);
            Map<String, String> original = new HashMap<>(builder.environment());
            builder.environment().clear();
            for (String key : List.of(
                    "PATH",
                    "Path",
                    "SystemRoot",
                    "WINDIR",
                    "USERPROFILE",
                    "HOME",
                    "APPDATA",
                    "LOCALAPPDATA",
                    "TEMP",
                    "TMP")) {
                if (original.containsKey(key)) {
                    builder.environment().put(key, original.get(key));
                }
            }
            Process process = builder.start();
            BoundedCapture out = new BoundedCapture(process.getInputStream(), outputLimit);
            BoundedCapture err = new BoundedCapture(process.getErrorStream(), 4096);
            Thread output = Thread.ofVirtual().start(out);
            Thread error = Thread.ofVirtual().start(err);
            Thread writer = Thread.ofVirtual().start(() -> {
                try (var stream = process.getOutputStream()) {
                    if (input != null) {
                        stream.write(input);
                    }
                } catch (IOException ignored) {
                }
            });
            try {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0 || !process.waitFor(remaining, TimeUnit.NANOSECONDS)) {
                    throw new IOException("docker_operation_timeout");
                }
                joinWithinDeadline(writer, deadline);
                joinWithinDeadline(output, deadline);
                joinWithinDeadline(error, deadline);
                if (writer.isAlive() || output.isAlive() || error.isAlive()) {
                    throw new IOException("docker_io_unsettled");
                }
                return new CommandResult(process.exitValue(), out.bytes.toByteArray(), out.truncated);
            } finally {
                process.destroyForcibly();
                process.getInputStream().close();
                process.getErrorStream().close();
                process.getOutputStream().close();
                // These are client I/O threads, never proof of container termination.
                writer.join(1000);
                output.join(1000);
                error.join(1000);
            }
        }

        private static void joinWithinDeadline(Thread thread, long deadline) throws InterruptedException {
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) {
                thread.join(Duration.ofNanos(remaining));
            }
        }
    }

    private static final class BoundedCapture implements Runnable {

        private final InputStream stream;
        private final int limit;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        volatile boolean truncated;

        private BoundedCapture(InputStream stream, int limit) {
            this.stream = stream;
            this.limit = limit;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[8192];
            try {
                int count;
                while ((count = stream.read(buffer)) >= 0) {
                    int kept = Math.min(count, Math.max(0, limit - bytes.size()));
                    bytes.write(buffer, 0, kept);
                    if (kept < count) {
                        truncated = true;
                    }
                }
            } catch (IOException ignored) {
            }
        }
    }
}
