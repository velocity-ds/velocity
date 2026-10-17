package net.velocity.agent.state;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Versioned state. Repository reads and writes return detached copies of this mutable document. */
public final class ExecutionSnapshot extends ConversationState {

    /**
     * The single snapshot format. Only this version is readable; the codec rejects every other version.
     * Version 5 changed the persisted shape of an execution: stored version 4 executions are not read.
     */
    public static final int FORMAT_VERSION = 5;
    public int formatVersion = FORMAT_VERSION;
    public AdmissionContext admissionContext;
    public List<MessageMetadata> messageMetadata = new ArrayList<>();
    /** Canonical projection intent: advances whenever original messages change. */
    public long historyGeneration;
    public Set<Integer> completedToolBatches = new LinkedHashSet<>();
    /** Only hook-modified requests need an additional exact durable copy. */
    public Map<Integer, List<net.velocity.inference.objects.InferenceMessage>> preparedRequests = new LinkedHashMap<>();
    public TaskPurpose purpose = TaskPurpose.generic;
    /**
     * The pinned inference binding, the optional admitted cloud fallback, the one-way cloud transition and
     * the latest compact request view. Replaced as a whole, never edited in place; null only on an
     * unadmitted document, which the codec rejects.
     */
    public InferenceState inference;
    /**
     * A tools-disabled final reporting mode, saved before its sole provider request; null outside the mode.
     * Its attempted flag is saved with the provider request intent.
     */
    public PartialReporting partialReporting;
    /** Unknown provider requests retain their cumulative budget reservation across recovery. */
    public Map<String, Long> providerReservations = new LinkedHashMap<>();
    /** Numeric, non-secret diagnostics for the last attempted model request. */
    public Map<String, Object> contextDiagnostics = new LinkedHashMap<>();
    public String executionId;
    public String conversationId;
    public String originalRequest;
    public String currentGoal;
    /** Optional final model declaration. Missing in legacy state; execution status retains its meaning. */
    public TaskOutcome taskOutcome;
    public TrustedScope scope;
    public SourceTurn sourceTurn;
    public MonitoringExecution monitoring;
    public List<SourceMessage> sourceMessages = new ArrayList<>();
    public List<SourceMessage> pendingSourceMessages = new ArrayList<>();
    public Set<SourceTurn> acceptedSourceTurns = new LinkedHashSet<>();
    public List<Correction> corrections = new ArrayList<>();
    public Set<String> consumedInboxIds = new LinkedHashSet<>();
    public List<Question> pendingQuestions = new ArrayList<>();
    public boolean automaticResumption = true;
    public boolean cancellationRequested;
    public long revision;
    public long deliveryTurn = 1;
    /** Content generation advances only on trusted user corrections or answers. */
    public long goalGeneration = 1;
    /** Includes admitted inbox records so crash recovery increments each correction exactly once. */
    public Set<String> goalGenerationInboxIds = new LinkedHashSet<>();
    /** Immutable output selections, separate from incoming inference attachments. */
    public List<OutputReportReference> outputReports = new ArrayList<>();
    /** Durable accepted-call results, retained when a correction invalidates current selection. */
    public Map<String, ReportStagingReceipt> reportStagingReceipts = new LinkedHashMap<>();
    public Map<String, ChildRun> childRuns = new LinkedHashMap<>();
    public int delegatedModelRequests;
    public int delegatedToolCalls;
    /** Reservations survive interrupted provider requests whose final usage is unknown. */
    public long delegatedReservedTokens;
    public int maxChildRuns = 8;
    public int maxChildStateBytes = 2 * 1024 * 1024;
    public Instant createdAt;
    public Instant updatedAt;
    /** Durable FIFO ticket assigned at admission; always positive. */
    public long queueSequence;
    /** Non-null only while this running execution is waiting for a root worker slot. */
    public Instant queuedAt;
    public String instructions;
    /**
     * The stopped execution this one continues, set once at admission; null for every other task. Optional
     * and additive: executions saved before the field existed decode with null, so format 5 still applies.
     */
    public String continuedFrom;

    public InferenceBinding activeInferenceBinding() {
        return inference == null ? null : inference.activeBinding();
    }

    public boolean usesTaskDeadlineOnly() {
        return purpose == TaskPurpose.generic && monitoring == null;
    }
}
