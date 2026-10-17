package net.velocity.agent.codeexecution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.json.JsonMapper;

import net.velocity.agent.*;
import net.velocity.agent.accounts.AccountRepository;
import net.velocity.agent.persistence.*;
import net.velocity.agent.state.*;
import net.velocity.inference.InferenceDeadlineScope;

/** Single-installation admission, exact durable receipts, and verified external worker settlement. */
public final class CodeExecutionService implements AutoCloseable {

    private static final JsonMapper JSON = new JsonMapper();
    private final CodeExecutionProperties properties;
    private final SandboxClient sandbox;
    private final CodeRunRepository runs;
    private final CodeArtifactService artifacts;
    private final ExecutionRepository executions;
    private final AccountRepository accounts;
    private final AuthorizationPolicy authorization;
    private final Clock clock;
    private final Semaphore admission = new Semaphore(5, true);
    private final Semaphore worker = new Semaphore(1, true);
    private final Set<String> active = ConcurrentHashMap.newKeySet();
    private volatile boolean closing;
    private ScheduledExecutorService recovery;

    public record Status(
            boolean enabled,
            String state,
            String reason,
            String runtimeProfile,
            Map<String, String> libraries,
            Map<String, Number> limits) {}

    public Status status() {
        String state = !properties.enabled()
                ? "disabled"
                : !properties.configured() ? "unconfigured" : "available";
        String reason = switch (state) {
            case "disabled" -> "Enable code execution after installing the approved runtime.";
            case "unconfigured" -> "Configure the installed immutable image and installation ID.";
            default -> "";
        };
        if (state.equals("available")) {
            try {
                if (!sandbox.availability().available()) {
                    state = "unavailable";
                    reason = "Check Docker Linux containers, the installed image, and required isolation support.";
                }
            } catch (RuntimeException unavailable) {
                state = "unavailable";
                reason = "The sandbox backend could not be checked.";
            }
        }
        return new Status(
                properties.enabled(),
                state,
                reason,
                SandboxClient.PROFILE,
                Map.of(
                        "pandas",
                        "2.3.3",
                        "NumPy",
                        "2.3.4",
                        "matplotlib",
                        "3.10.7",
                        "openpyxl",
                        "3.1.5",
                        "pypdf",
                        "6.10.0",
                        "pdfplumber",
                        "0.11.9",
                        "reportlab",
                        "4.4.9",
                        "pypdfium2",
                        "5.13.0"),
                Map.ofEntries(
                        Map.entry("cpu", 1),
                        Map.entry("memoryMiB", 512),
                        Map.entry("processes", 64),
                        Map.entry("storageMiB", 128),
                        Map.entry("sourceBytes", SandboxClient.MAX_SOURCE_BYTES),
                        Map.entry("stdoutBytes", SandboxClient.MAX_STREAM_BYTES),
                        Map.entry("stderrBytes", SandboxClient.MAX_STREAM_BYTES),
                        Map.entry("inputFiles", 10),
                        Map.entry("inputBytes", SandboxClient.MAX_INPUT_BYTES),
                        Map.entry("outputFiles", 10),
                        Map.entry("outputFileBytes", SandboxClient.MAX_FILE_BYTES),
                        Map.entry("outputTotalBytes", SandboxClient.MAX_OUTPUT_BYTES),
                        Map.entry("concurrency", 1),
                        Map.entry("queueCapacity", 4)));
    }

    public CodeExecutionService(
            CodeExecutionProperties properties,
            SandboxClient sandbox,
            CodeRunRepository runs,
            CodeArtifactService artifacts,
            ExecutionRepository executions,
            AccountRepository accounts,
            AuthorizationPolicy authorization,
            Clock clock) {
        this.properties = properties;
        this.sandbox = sandbox;
        this.runs = runs;
        this.artifacts = artifacts;
        this.executions = executions;
        this.accounts = accounts;
        this.authorization = authorization;
        this.clock = clock;
    }

    public Object execute(
            ToolRegistry.Context context,
            Map<String, Object> input,
            ToolRegistry.Definition definition) {
        String id = runId(context.executionId(), context.acceptedCallId());
        CodeRunRepository.Run run = null;
        SandboxClient.RunSpec spec = null;
        boolean admitted = false, acquired = false, attempted = false, registered = false;
        long deadline = InferenceDeadlineScope.captureDeadline(context.remainingOperationMillis());
        try {
            if (!properties.enabled()) {
                return failure(id, "disabled");
            }
            if (!properties.configured()) {
                return failure(id, "unconfigured");
            }
            JsonSchema.validate(CodeExecutionTools.inputSchema(), input);
            var initial = current(context, input, definition, null);
            if (initial.limits.outputPreviewChars() < 8192) {
                return failure(id, "result_capacity");
            }
            deadline = Math.min(
                    deadline,
                    System.nanoTime() + TimeUnit.MILLISECONDS
                            .toNanos(Math.max(0, initial.limits.activeMillis() - initial.activeMillis)));
            var prior = runs.find(id);
            if (prior.isPresent()) {
                return existing(context, input, prior.get(), initial.limits.outputPreviewChars());
            }
            // Admission also bounds staged input memory, not only running containers.
            if (!admission.tryAcquire()) {
                return failure(id, "queue_full");
            }
            admitted = true;
            registered = active.add(id);
            if (!registered) {
                throw uncertain();
            }
            String code = (String) input.get("code");
            if (code.getBytes(StandardCharsets.UTF_8).length > SandboxClient.MAX_SOURCE_BYTES) {
                return failure(id, "source_limit");
            }
            List<String> outputs = outputNames(input);
            var resolved = new ArrayList<CodeArtifactService.ResolvedInput>();
            var names = new HashSet<String>();
            long bytes = 0;
            for (Object item : (List<?>) input.getOrDefault("input_artifacts", List.of())) {
                current(context, input, definition, initial.goalGeneration);
                if (remaining(deadline) < 1) {
                    throw new Stop("budget_exhausted");
                }
                var fields = (Map<?, ?>) item;
                String name = (String) fields.get("filename");
                validFilename(name);
                if (!names.add(name.toLowerCase(Locale.ROOT))) {
                    return failure(id, "duplicate_filename");
                }
                var source = artifacts.resolve(context, (String) fields.get("artifact_id"), name);
                bytes += source.bytes().length;
                if (bytes > SandboxClient.MAX_INPUT_BYTES) {
                    return failure(id, "input_limit");
                }
                resolved.add(source);
            }
            var at = clock.instant();
            run = new CodeRunRepository.Run(
                    id,
                    context.scope().requireOwnerAccountId(),
                    context.executionId(),
                    context.acceptedCallId(),
                    context.acceptedCallBinding(),
                    initial.goalGeneration,
                    properties.image(),
                    hash(code),
                    resolved.stream().map(CodeArtifactService.ResolvedInput::provenance).toList(),
                    outputs,
                    encode(input),
                    "velocity-code-" + id,
                    "admitted",
                    null,
                    false,
                    1,
                    at,
                    at);
            if (!runs.insert(run)) {
                return existing(
                        context,
                        input,
                        runs.find(id).orElseThrow(),
                        initial.limits.outputPreviewChars());
            }
            while (!(acquired = worker.tryAcquire(50, TimeUnit.MILLISECONDS))) {
                current(context, input, definition, run.goalGeneration());
                if (remaining(deadline) < 1) {
                    throw new Stop("queue_deadline");
                }
            }
            current(context, input, definition, run.goalGeneration());
            // An unknown surviving worker continues consuming this installation's capacity.
            if (runs.recoverable(1000).stream().anyMatch(
                    other -> !other.runId().equals(id)
                            && !active.contains(other.runId()) && !"admitted".equals(other.state())
                            && other.cleanupPending())) {
                throw new Stop("cleanup_required");
            }
            if (!sandbox.availability().available()) {
                throw new Stop("runner_unavailable");
            }
            long executionMillis = remaining(deadline);
            if (executionMillis < 1) {
                throw new Stop("budget_exhausted");
            }
            spec = new SandboxClient.RunSpec(
                    run.resourceName(),
                    id,
                    code,
                    resolved.stream()
                            .map(source -> new SandboxClient.InputFile(source.filename(), source.bytes()))
                            .toList(),
                    outputs,
                    executionMillis,
                    deadline,
                    run.runtimeImage());
            run = save(run, "creating", null, true);
            var expected = run;
            long dispatchDeadline = deadline;
            // Persist/check dispatch authority under the task lock, then release it before remote I/O.
            // Cancellation must be able to save its intent and interrupt a slow Docker create/start.
            executions.update(context.executionId(), -1, snapshot -> {
                current(context, input, definition, expected.goalGeneration());
                if (remaining(dispatchDeadline) < 1) {
                    throw new Stop("budget_exhausted");
                }
            });
            current(context, input, definition, expected.goalGeneration());
            attempted = true;
            sandbox.createAndStart(spec);
            run = save(run, "running", null, true);
            SandboxClient.Result result;
            var localCancellation = new CancellationHandle();
            long expectedGeneration = run.goalGeneration();
            long effectiveDeadline = deadline;
            Thread guard = Thread.ofVirtual().name("code-run-guard").start(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        if (remaining(effectiveDeadline) < 1) {
                            localCancellation.cancel();
                            return;
                        }
                        try {
                            current(context, input, definition, expectedGeneration);
                        } catch (RuntimeException stopped) {
                            localCancellation.cancel();
                            return;
                        }
                        Thread.sleep(50);
                    }
                } catch (InterruptedException finished) {
                    Thread.currentThread().interrupt();
                }
            });
            try {
                result = sandbox.await(spec, localCancellation);
            } finally {
                guard.interrupt();
                guard.join(1000);
            }
            if (!result.terminationConfirmed()) {
                result = sandbox.terminate(spec);
            }
            if (!result.terminationConfirmed()) {
                throw new BackendFailure();
            }
            run = save(run, "collecting", null, true);
            return finish(context, input, definition, run, result, spec, initial.limits.outputPreviewChars());
        } catch (Exception failure) {
            boolean interrupted = Thread.interrupted();
            try {
                if (failure instanceof ToolRegistry.UncertainToolException unknown) {
                    throw unknown;
                }
                if (run == null) {
                    return failure(id, failureCode(failure));
                }
                var saved = runs.find(id).orElse(run);
                if (saved.receiptJson() != null) {
                    return modelReceipt(saved.receiptJson(), previewBudget(context.executionId()));
                }
                if (attempted && spec != null) {
                    try {
                        var stopped = sandbox.terminate(spec);
                        if (stopped.terminationConfirmed()) {
                            String status = context.cancellation().cancelled() || interrupted
                                    ? "cancelled"
                                    : "interrupted";
                            return finishFailure(saved, status, spec, previewBudget(context.executionId()));
                        }
                    } catch (Exception unavailable) {
                        /* Keep exact intent for the lifecycle recovery worker. */ }
                    save(saved, "uncertain", null, true);
                    throw uncertain();
                }
                return finishFailure(saved, failureCode(failure), null, previewBudget(context.executionId()));
            } catch (ToolRegistry.UncertainToolException uncertain) {
                throw uncertain;
            } catch (Exception persistenceUnavailable) {
                throw uncertain();
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        } finally {
            if (registered) {
                active.remove(id);
            }
            if (acquired) {
                worker.release();
            }
            if (admitted) {
                admission.release();
            }
        }
    }

    private Object finish(
            ToolRegistry.Context context,
            Map<String, Object> input,
            ToolRegistry.Definition definition,
            CodeRunRepository.Run run,
            SandboxClient.Result result,
            SandboxClient.RunSpec spec,
            int preview) {
        var receipt = new AtomicReference<Map<String, Object>>();
        var terminal = new AtomicReference<CodeRunRepository.Run>();
        executions.update(run.executionId(), -1, snapshot -> {
            Map<String, Object> values = result(run.runId(), result);
            boolean publish = true;
            try {
                current(context, input, definition, run.goalGeneration());
            } catch (RuntimeException stale) {
                values = failure(run.runId(), failureCode(stale));
                publish = false;
            }
            var manifest = new ArrayList<Map<String, Object>>();
            if (publish) {
                var exportedNames = new HashSet<String>();
                try {
                    for (var output : result.outputFiles()) {
                        current(context, input, definition, run.goalGeneration());
                        if (!run.outputFiles().contains(output.filename())
                                || !exportedNames.add(output.filename())) {
                            throw new IllegalArgumentException("Unexpected or duplicate output");
                        }
                        manifest.add(
                                CodeArtifactService
                                        .metadata(artifacts.publish(run, output.filename(), output.bytes())));
                    }
                    if ("succeeded".equals(values.get("status"))
                            && !exportedNames.equals(new HashSet<>(run.outputFiles()))) {
                        values.put("status", "missing_outputs");
                        values.put("failure_code", "missing_outputs");
                    }
                } catch (RuntimeException partial) {
                    values.put("status", "artifact_publication_failed");
                    values.put("failure_code", "artifact_publication_failed");
                    manifest.clear();
                    artifacts.listRun(run.executionId(), run.runId())
                            .forEach(a -> manifest.add(CodeArtifactService.metadata(a)));
                }
            }
            values.put("output_artifacts", manifest);
            terminal.set(save(run, "terminal", encode(values), true));
            receipt.set(values);
        });
        cleanup(terminal.get(), spec);
        return modelReceipt(encode(receipt.get()), preview);
    }

    private Object finishFailure(
            CodeRunRepository.Run run,
            String code,
            SandboxClient.RunSpec spec,
            int preview) {
        var receipt = failure(run.runId(), code);
        receipt.put(
                "output_artifacts",
                artifacts.listRun(run.executionId(), run.runId()).stream().map(CodeArtifactService::metadata)
                        .toList());
        var saved = save(run, "terminal", encode(receipt), spec != null);
        if (spec != null) {
            cleanup(saved, spec);
        }
        return modelReceipt(encode(receipt), preview);
    }

    /** Exact durable receipt only. No Docker access, lifecycle writes, or redispatch. */
    public Optional<Object> reconcile(ToolRegistry.Context context, Map<String, Object> input) {
        requireBinding(context, input);
        accounts.verify(context.scope());
        var run = runs.find(runId(context.executionId(), context.acceptedCallId()));
        if (run.isEmpty()) {
            return Optional.empty();
        }
        verifyIdentity(context, input, run.get());
        return run.get().receiptJson() == null
                ? Optional.empty()
                : Optional.of(modelReceipt(run.get().receiptJson(), previewBudget(context.executionId())));
    }

    private Object existing(
            ToolRegistry.Context context,
            Map<String, Object> input,
            CodeRunRepository.Run prior,
            int budget) {
        verifyIdentity(context, input, prior);
        if (prior.receiptJson() == null) {
            throw uncertain();
        }
        return modelReceipt(prior.receiptJson(), budget);
    }

    private ExecutionSnapshot current(
            ToolRegistry.Context context,
            Map<String, Object> input,
            ToolRegistry.Definition definition,
            Long generation) {
        requireBinding(context, input);
        accounts.verify(context.scope());
        var snapshot = executions.get(context.executionId());
        if (!snapshot.scope.equals(context.scope()) || snapshot.monitoring != null
                || context.childRunId() != null) {
            throw new Stop("permission_denied");
        }
        var call = snapshot.calls.stream().filter(c -> c.id.equals(context.acceptedCallId())).findFirst()
                .orElseThrow(() -> new Stop("binding_changed"));
        if (call.state != CallState.dispatched || !Objects.equals(call.binding, context.acceptedCallBinding())
                || !"code_run".equals(call.name)
                || !AuthorizationPolicy.binding(context.scope(), call.id, "code_run", call.input)
                        .equals(call.binding)) {
            throw new Stop("binding_changed");
        }
        if (authorization
                .authorize(context.scope(), definition, call) != AuthorizationPolicy.Decision.allow) {
            throw new Stop("permission_denied");
        }
        if (snapshot.admissionContext != null && snapshot.admissionContext.tools().stream()
                .noneMatch(pinned -> pinned.name().equals("code_run") && pinned.compatible(definition))) {
            throw new Stop("binding_changed");
        }
        if (closing || snapshot.cancellationRequested || context.cancellation().cancelled()
                || Thread.currentThread().isInterrupted()) {
            throw new Stop("cancelled");
        }
        if (snapshot.status.terminal() || call.goalGeneration != snapshot.goalGeneration
                || generation != null && generation != snapshot.goalGeneration
                || executions.inbox(snapshot.executionId).stream()
                        .anyMatch(c -> !snapshot.consumedInboxIds.contains(c.id()))) {
            throw new Stop("superseded");
        }
        if (context.remainingOperationMillis() == 0) {
            throw new Stop("budget_exhausted");
        }
        return snapshot;
    }

    /** Startup recovery runs before gateway recovery; periodic retries retain cleanup responsibility. */
    public synchronized void start() {
        if (!properties.enabled() || !properties.configured()) {
            return;
        }
        recover();
        recovery = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("code-run-recovery").factory());
        recovery.scheduleWithFixedDelay(this::recover, 5, 5, TimeUnit.SECONDS);
    }

    public void recover() {
        if (!properties.enabled() || !properties.configured() || closing) {
            return;
        }
        try {
            for (var run : runs.recoverable(1000)) {
                if (active.contains(run.runId())) {
                    continue;
                }
                try {
                    var spec = recoverySpec(run);
                    if (run.receiptJson() != null) {
                        cleanup(run, spec);
                        continue;
                    }
                    if ("admitted".equals(run.state())) {
                        finishFailure(run, "interrupted_before_dispatch", null, 4096);
                        continue;
                    }
                    var observed = sandbox.inspect(spec);
                    if (!observed.terminationConfirmed()) {
                        observed = sandbox.terminate(spec);
                    }
                    if (!observed.terminationConfirmed()) {
                        save(run, "uncertain", null, true);
                        continue;
                    }
                    // Recovery never publishes a stale result as the current goal. Preserve available evidence and partial manifest.
                    var receipt = result(run.runId(), observed);
                    if ("succeeded".equals(observed.status()) && !run.outputFiles().isEmpty()) {
                        receipt.put("status", "artifact_publication_failed");
                        receipt.put("failure_code", "interrupted_collection");
                    }
                    receipt.put(
                            "output_artifacts",
                            artifacts.listRun(run.executionId(), run.runId()).stream()
                                    .map(CodeArtifactService::metadata).toList());
                    var terminal = save(run, "terminal", encode(receipt), true);
                    cleanup(terminal, spec);
                } catch (Exception unavailable) {
                    /* Journal retains exact ownership and cleanup responsibility. */ }
            }
        } catch (RuntimeException unavailable) {
            /* No exception detail, code or task data goes to general logs. */ }
    }

    public boolean settled(String executionId) {
        if (executions.get(executionId).calls.stream().noneMatch(call -> "code_run".equals(call.name))) {
            return true;
        }
        return runs.listExecution(executionId, 1000).stream()
                .noneMatch(r -> active.contains(r.runId()) || r.receiptJson() == null);
    }

    public void deleteExecution(String executionId) {
        if (executions.get(executionId).calls.stream().noneMatch(call -> "code_run".equals(call.name))) {
            return;
        }
        if (!settled(executionId)) {
            throw new ConflictException("Code worker termination is not confirmed");
        }
        for (var run : runs.listExecution(executionId, 1000)) {
            if (run.cleanupPending()) {
                if (!properties.enabled() || !properties.configured()) {
                    throw new ConflictException("Code sandbox cleanup requires the configured backend");
                }
                cleanup(run, recoverySpec(run));
                if (runs.find(run.runId()).orElseThrow().cleanupPending()) {
                    throw new ConflictException("Code sandbox cleanup is pending");
                }
            }
        }
        artifacts.deleteExecution(executionId);
        runs.deleteExecution(executionId);
    }

    private void cleanup(CodeRunRepository.Run run, SandboxClient.RunSpec spec) {
        try {
            if (sandbox.remove(spec)) {
                save(run, run.state(), run.receiptJson(), false);
            }
        } catch (Exception pending) {
            /* Never discard evidence before acknowledged removal. */ }
    }

    private CodeRunRepository.Run save(
            CodeRunRepository.Run run,
            String state,
            String receipt,
            boolean cleanup) {
        var next = run.advance(state, receipt, cleanup, clock.instant());
        if (!runs.compareAndSet(next, run.revision())) {
            throw new ConflictException("Code run revision changed");
        }
        return next;
    }

    private static SandboxClient.RunSpec recoverySpec(CodeRunRepository.Run run) {
        return new SandboxClient.RunSpec(
                run.resourceName(),
                run.runId(),
                "",
                List.of(),
                run.outputFiles(),
                0,
                Long.MAX_VALUE,
                run.runtimeImage());
    }

    private static void requireBinding(ToolRegistry.Context context, Map<String, Object> input) {
        if (context.acceptedCallId().isBlank() || context.childRunId() != null
                || !AuthorizationPolicy.binding(context.scope(), context.acceptedCallId(), "code_run", input)
                        .equals(context.acceptedCallBinding())) {
            throw new Stop("binding_changed");
        }
    }

    private static void verifyIdentity(
            ToolRegistry.Context context,
            Map<String, Object> input,
            CodeRunRepository.Run run) {
        requireBinding(context, input);
        if (!run.executionId().equals(context.executionId())
                || !run.ownerAccountId().equals(context.scope().requireOwnerAccountId())
                || !run.acceptedCallId().equals(context.acceptedCallId())
                || !run.acceptedCallBinding().equals(context.acceptedCallBinding())) {
            throw new Stop("binding_changed");
        }
    }

    static String runId(String execution, String acceptedCall) {
        return UUID.nameUUIDFromBytes((execution + "\n" + acceptedCall).getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    private static List<String> outputNames(Map<String, Object> input) {
        var names = new ArrayList<String>();
        var unique = new HashSet<String>();
        for (Object item : (List<?>) input.getOrDefault("output_files", List.of())) {
            String name = (String) item;
            validFilename(name);
            if (!unique.add(name.toLowerCase(Locale.ROOT))) {
                throw new Stop("duplicate_filename");
            }
            names.add(name);
        }
        return List.copyOf(names);
    }

    static void validFilename(String name) {
        try {
            CodeFiles.filename(name);
        } catch (IllegalArgumentException invalid) {
            throw new Stop("invalid_filename");
        }
    }

    private static long remaining(long deadline) {
        return Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
    }

    private static String hash(String code) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String encode(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid code receipt");
        }
    }

    static Map<String, Object> decode(String value) {
        try {
            return JSON.readValue(value, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid code receipt");
        }
    }

    static Map<String, Object> failure(String id, String code) {
        var values = result(
                id,
                new SandboxClient.Result(code, null, "", "", false, false, 0, List.of(), code, true));
        return values;
    }

    private static Map<String, Object> result(String id, SandboxClient.Result result) {
        var values = new LinkedHashMap<String, Object>();
        values.put("run_id", id);
        values.put("status", result.status());
        if (result.exitCode() != null) {
            values.put("exit_code", result.exitCode());
        }
        values.put("stdout", bounded(result.stdout(), SandboxClient.MAX_STREAM_BYTES));
        values.put("stderr", bounded(result.stderr(), SandboxClient.MAX_STREAM_BYTES));
        values.put("stdout_truncated", result.stdoutTruncated());
        values.put("stderr_truncated", result.stderrTruncated());
        values.put("duration_ms", Math.max(0, result.durationMillis()));
        values.put("output_artifacts", List.of());
        values.put("content_trust", "untrusted_data");
        if (result.failureCode() != null) {
            values.put("failure_code", result.failureCode());
        }
        return values;
    }

    private int previewBudget(String execution) {
        try {
            return executions.get(execution).limits.outputPreviewChars();
        } catch (RuntimeException absent) {
            return 4096;
        }
    }

    private static Map<String, Object> modelReceipt(String json, int budget) {
        var values = decode(json);
        for (String stream : List.of("stdout", "stderr")) {
            String text = Objects.toString(values.get(stream), "");
            int limit = Math.max(0, Math.min(2048, (budget - 4096) / 12));
            if (text.length() > limit) {
                values.put(stream, bounded(text, limit));
                values.put(stream + "_truncated", true);
            }
        }
        while (encode(values).length() > budget && (!Objects.toString(values.get("stdout"), "").isEmpty()
                || !Objects.toString(values.get("stderr"), "").isEmpty())) {
            for (String stream : List.of("stdout", "stderr")) {
                String text = Objects.toString(values.get(stream), "");
                if (!text.isEmpty()) {
                    values.put(stream, bounded(text, text.length() / 2));
                    values.put(stream + "_truncated", true);
                }
            }
        }
        return values;
    }

    private static String bounded(String value, int limit) {
        return value == null ? "" : value.substring(0, Math.min(limit, value.length()));
    }

    private static String failureCode(Exception failure) {
        return failure instanceof Stop stop
                ? stop.code
                : failure instanceof SecurityException
                        ? "permission_denied"
                        : failure instanceof InterruptedException || failure instanceof CancellationException
                                ? "cancelled"
                                : "invalid_request";
    }

    private static ToolRegistry.UncertainToolException uncertain() {
        return new ToolRegistry.UncertainToolException(Map.of("code", "code_run_uncertain"));
    }

    private static final class Stop extends IllegalStateException {

        final String code;

        Stop(String code) {
            super(code);
            this.code = code;
        }
    }

    private static final class BackendFailure extends IllegalStateException {}

    @Override
    public void close() {
        closing = true;
        if (recovery != null) {
            recovery.shutdownNow();
        }
        // Guards request verified termination; unresolved workers retain their durable journal.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(35);
        while (!active.isEmpty() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
