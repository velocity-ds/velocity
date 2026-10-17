package net.velocity.agent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

import com.fasterxml.jackson.annotation.JsonAlias;

import net.velocity.agent.state.TrustedScope;
import net.velocity.agent.tools.AskUserTool;
import net.velocity.agent.tools.DateTimeTool;
import net.velocity.inference.objects.InferenceResponse;

/** Trusted Java registrations own schemas and execution properties; discovery confers no permission. */
public final class ToolRegistry {

    @FunctionalInterface
    public interface UsageReporter {

        void report(String sourceId, InferenceResponse.Usage cumulativeUsage, boolean missing);
    }

    /** The task token budget cannot cover another nested provider request; the tool must stop requesting. */
    public static final class ProviderRequestLimitException extends RuntimeException {

        public ProviderRequestLimitException(String message) {
            super(message);
        }
    }

    /**
     * Trusted admission of an actual nested provider request, distinct from usage observation. Throws
     * {@link ProviderRequestLimitException} when the reservation does not fit the remaining task budget.
     */
    @FunctionalInterface
    public interface ProviderRequestAdmission {

        void admit(String sourceId);

        default void admit(String sourceId, long reservedTokens) {
            admit(sourceId);
        }
    }

    public record Context(
            String executionId,
            TrustedScope scope,
            CancellationHandle cancellation,
            Consumer<String> progressReporter,
            UsageReporter usageReporter,
            String acceptedCallId,
            String acceptedCallBinding,
            String childRunId,
            ProviderRequestAdmission providerRequestAdmission,
            long operationDeadlineNanos) {

        /**
         * Starts a context with no accepted model call, inert reporters, no child run, no-op provider
         * admission and no operation deadline. Direct callers must never invent gateway provenance.
         */
        public static Builder builder() {
            return new Builder();
        }

        /** Builds a {@link Context}; the canonical constructor still owns every validation rule. */
        public static final class Builder {

            private String executionId;
            private TrustedScope scope;
            private CancellationHandle cancellation;
            private Consumer<String> progressReporter = ignored -> {};
            private UsageReporter usageReporter = (source, usage, missing) -> {};
            private String acceptedCallId = "";
            private String acceptedCallBinding = "";
            private String childRunId;
            private ProviderRequestAdmission providerRequestAdmission = ignored -> {};
            private long operationDeadlineNanos = Long.MAX_VALUE;

            private Builder() {
            }

            public Builder executionId(String executionId) {
                this.executionId = executionId;
                return this;
            }

            public Builder scope(TrustedScope scope) {
                this.scope = scope;
                return this;
            }

            public Builder cancellation(CancellationHandle cancellation) {
                this.cancellation = cancellation;
                return this;
            }

            public Builder progressReporter(Consumer<String> progressReporter) {
                this.progressReporter = progressReporter;
                return this;
            }

            public Builder usageReporter(UsageReporter usageReporter) {
                this.usageReporter = usageReporter;
                return this;
            }

            /** Accepted call identity and binding are supplied together or not at all. */
            public Builder acceptedCall(String acceptedCallId, String acceptedCallBinding) {
                this.acceptedCallId = acceptedCallId;
                this.acceptedCallBinding = acceptedCallBinding;
                return this;
            }

            public Builder childRunId(String childRunId) {
                this.childRunId = childRunId;
                return this;
            }

            public Builder providerRequestAdmission(ProviderRequestAdmission providerRequestAdmission) {
                this.providerRequestAdmission = providerRequestAdmission;
                return this;
            }

            public Builder operationDeadlineNanos(long operationDeadlineNanos) {
                this.operationDeadlineNanos = operationDeadlineNanos;
                return this;
            }

            public Context build() {
                return new Context(
                        executionId,
                        scope,
                        cancellation,
                        progressReporter,
                        usageReporter,
                        acceptedCallId,
                        acceptedCallBinding,
                        childRunId,
                        providerRequestAdmission,
                        operationDeadlineNanos);
            }
        }

        /** The executor's original monotonic deadline includes admission and queue waits. */
        public long remainingOperationMillis() {
            return operationDeadlineNanos == Long.MAX_VALUE
                    ? Long.MAX_VALUE
                    : Math.max(
                            0,
                            java.util.concurrent.TimeUnit.NANOSECONDS
                                    .toMillis(operationDeadlineNanos - System.nanoTime()));
        }

        public Context {
            acceptedCallId = Objects.requireNonNullElse(acceptedCallId, "");
            acceptedCallBinding = Objects.requireNonNullElse(acceptedCallBinding, "");
            if (acceptedCallId.isEmpty() != acceptedCallBinding.isEmpty()) {
                throw new IllegalArgumentException(
                        "Accepted call identity and binding must be supplied together");
            }
            if (childRunId != null
                    && (childRunId.isBlank() || childRunId.length() > 128 || acceptedCallId.isBlank())) {
                throw new IllegalArgumentException("Child run identity requires accepted call provenance");
            }
            Objects.requireNonNull(providerRequestAdmission);
        }

        /** Report concise operational activity; do not include private reasoning or tool output. */
        public void progress(String text) {
            progressReporter.accept(text);
        }

        /** Report a stable child/job ID and provider-normalized cumulative totals; null means unavailable. */
        public void reportUsage(String sourceId, InferenceResponse.Usage cumulativeUsage) {
            reportUsage(sourceId, cumulativeUsage, cumulativeUsage == null);
        }

        public void reportUsage(String sourceId, InferenceResponse.Usage cumulativeUsage, boolean missing) {
            usageReporter.report(sourceId, cumulativeUsage, missing);
        }

        public void admitProviderRequest(String sourceId) {
            providerRequestAdmission.admit(sourceId);
        }

        public void admitProviderRequest(String sourceId, long reservedTokens) {
            providerRequestAdmission.admit(sourceId, reservedTokens);
        }
    }

    @FunctionalInterface
    public interface Handler {

        Object execute(Context context, Map<String, Object> input) throws Exception;
    }

    /** A trusted read can serialize publication with revocation of its external account connection. */
    @FunctionalInterface
    public interface ReadPublicationGuard {

        boolean publish(Runnable publication);
    }

    /** Map entries alone are model-visible; this process-local guard is never persisted or serialized. */
    public static final class GuardedReadResult extends AbstractMap<String, Object> {

        private final Map<String, Object> values;
        private final ReadPublicationGuard guard;

        public GuardedReadResult(Map<String, Object> values, ReadPublicationGuard guard) {
            this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
            this.guard = Objects.requireNonNull(guard);
        }

        @Override
        public Set<Entry<String, Object>> entrySet() {
            return values.entrySet();
        }

        public ReadPublicationGuard publicationGuard() {
            return guard;
        }

        @Override
        public String toString() {
            return "GuardedReadResult[REDACTED]";
        }
    }

    /** Trusted adapters can preserve a validated native failure result without reporting success. */
    public interface FailureAwareHandler extends Handler {

        boolean failed(Object output);
    }

    /** Read contracts whose complete core must fit a single result; never silently truncate. */
    public interface CompleteResultHandler extends Handler {}

    /** Read-only destination inspection for an atomic, exact-bound mutation receipt. Never redispatches a write. */
    public interface ReconcilingHandler extends Handler {

        Optional<Object> reconcile(Context context, Map<String, Object> input) throws Exception;
    }

    /**
     * A write that always waits for the requester's confirmation before it runs, whatever the approval
     * policy's category settings say, and that can describe the pending call in plain language.
     *
     * <p>{@link #preview} receives the validated model input together with a read-only context and returns
     * the text shown to the requester next to the Approve and Decline controls: exactly what will be saved,
     * changed or removed, rendered by trusted Java from the actual arguments rather than by the model. It
     * must be side-effect free and should be total; a thrown exception only loses the preview, never the
     * hold. Unattended executions (scheduled occurrences, monitoring runs and delegated children) have no
     * requester to confirm, so a confirming tool runs there without a pause unless the approval policy's
     * own rules also hold it.
     */
    public interface ConfirmingHandler extends Handler {

        String preview(Context context, Map<String, Object> input) throws Exception;
    }

    /**
     * Trusted registration metadata for unattended observation; no model argument can grant it.
     *
     * <p>This value is persisted in each task's admitted tool contract, so the constant names are part of the
     * saved form. {@code restricted} is the former {@code browser_navigation}; saved contracts that still carry
     * the old name are read as {@code restricted}.
     */
    public enum MonitoringAccess {
        /** Never available to a monitoring execution. */
        denied,
        /** Available to a monitoring execution for every input. */
        observation,
        /**
         * Available only for inputs accepted by the definition's monitoring input filter, which the
         * registration must supply.
         */
        @JsonAlias("browser_navigation")
        restricted
    }

    /**
     * @param monitoringInput input filter for {@link MonitoringAccess#restricted}: required for that access and
     *        forbidden for every other; it is trusted registration metadata, never persisted, and is called with
     *        the model-supplied input (possibly {@code null}), so it must be total and side-effect free
     */
    public record Definition(
            String name,
            String description,
            Map<String, Object> inputSchema,
            Map<String, Object> outputSchema,
            String category,
            boolean trusted,
            boolean scoped,
            boolean cacheable,
            long timeoutMillis,
            MonitoringAccess monitoringAccess,
            Predicate<Map<String, Object>> monitoringInput,
            Handler handler) {

        public Definition(
                String name,
                String description,
                Map<String, Object> inputSchema,
                Map<String, Object> outputSchema,
                String category,
                boolean trusted,
                boolean scoped,
                boolean cacheable,
                long timeoutMillis,
                MonitoringAccess monitoringAccess,
                Handler handler) {
            this(
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
                    null,
                    handler);
        }

        public Definition(
                String name,
                String description,
                Map<String, Object> inputSchema,
                Map<String, Object> outputSchema,
                String category,
                boolean trusted,
                boolean scoped,
                boolean cacheable,
                long timeoutMillis,
                Handler handler) {
            this(
                    name,
                    description,
                    inputSchema,
                    outputSchema,
                    category,
                    trusted,
                    scoped,
                    cacheable,
                    timeoutMillis,
                    Set.of("repository.read", "network.read", "system.read", "agent.question")
                            .contains(category) ? MonitoringAccess.observation : MonitoringAccess.denied,
                    handler);
        }

        public Definition {
            if (name == null || !name.matches("[a-zA-Z0-9_-]{1,64}")) {
                throw new IllegalArgumentException("Invalid tool name");
            }
            Objects.requireNonNull(handler);
            Objects.requireNonNull(category);
            Objects.requireNonNull(monitoringAccess);
            if ((monitoringAccess == MonitoringAccess.restricted) != (monitoringInput != null)) {
                throw new IllegalArgumentException(
                        "Restricted monitoring access requires an input filter, and no other access may have one");
            }
            inputSchema = Map.copyOf(inputSchema);
            outputSchema = Map.copyOf(outputSchema);
            JsonSchema.check(inputSchema);
            JsonSchema.check(outputSchema);
            if (timeoutMillis < 0) {
                throw new IllegalArgumentException("Invalid tool timeout");
            }

        }

        public boolean parallelRead() {
            return trusted && !scoped && cacheable
                    && Set.of("repository.read", "network.read").contains(category);
        }

        public boolean read() {
            return Set.of("repository.read", "network.read", "system.read").contains(category);
        }

        /** True when the registered handler asks for the requester's confirmation on every call. */
        public boolean requiresConfirmation() {
            return handler instanceof ConfirmingHandler;
        }

        public boolean monitoringAllowed(Map<String, Object> input) {
            if (!trusted || monitoringAccess == MonitoringAccess.denied) {
                return false;
            }
            if (monitoringAccess != MonitoringAccess.restricted) {
                return true;
            }
            return monitoringInput.test(input);
        }
    }

    public static final class TransientReadException extends Exception {

        public TransientReadException() {
            super("Transient read failure");
        }
    }

    /**
     * Trusted handlers may retain bounded machine diagnostics when an external effect is uncertain.
     * Values must be application-owned vocabulary, booleans or counts, never remote text or exception messages.
     * This does not authorize replay or turn an uncertain mutation into a successful tool result.
     */
    public static class UncertainToolException extends IllegalStateException {

        private final Map<String, Object> diagnostic;

        public UncertainToolException(Map<String, Object> diagnostic) {
            super("External outcome unknown; destination inspection required before another mutation", null);
            if (diagnostic == null || diagnostic.isEmpty() || diagnostic.size() > 16) {
                throw new IllegalArgumentException("Invalid tool diagnostic");
            }
            diagnostic.forEach((key, value) -> {
                if (key == null || !key.matches("[a-z_]{1,64}")
                        || !(value instanceof Boolean
                                || value instanceof String text && text.matches("[A-Za-z0-9_]{1,64}")
                                || value instanceof Integer number && number >= 0 && number <= 1_000_000)) {
                    throw new IllegalArgumentException("Invalid tool diagnostic field");
                }
            });
            this.diagnostic = Map.copyOf(diagnostic);
        }

        public final Map<String, Object> diagnostic() {
            return diagnostic;
        }
    }

    private final Map<String, Definition> definitions = new ConcurrentHashMap<>();

    public ToolRegistry register(Definition definition) {
        if (definitions.putIfAbsent(definition.name(), definition) != null) {
            throw new IllegalArgumentException("Duplicate tool");
        }
        return this;
    }

    public Optional<Definition> find(String name) {
        return Optional.ofNullable(definitions.get(name));
    }

    public List<Definition> definitions() {
        return definitions.values().stream().sorted(Comparator.comparing(Definition::name)).toList();
    }

    public static ToolRegistry standard() {
        return standard(java.time.Clock.system(net.velocity.ApplicationTime.zone()));
    }

    public static ToolRegistry standard(java.time.Clock clock) {
        return new ToolRegistry().register(AskUserTool.definition())
                .register(DateTimeTool.definition(clock));
    }

}
