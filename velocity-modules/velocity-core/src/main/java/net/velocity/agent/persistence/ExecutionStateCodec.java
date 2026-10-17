package net.velocity.agent.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.bson.Document;
import org.bson.RawBsonDocument;
import org.bson.codecs.DocumentCodec;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.velocity.agent.AuthorizationPolicy;
import net.velocity.agent.delegation.DelegationProfileRegistry;
import net.velocity.agent.state.CallState;
import net.velocity.agent.state.ChildRun;
import net.velocity.agent.state.ConversationState;
import net.velocity.agent.state.Correction;
import net.velocity.agent.state.ExecutionSnapshot;
import net.velocity.agent.state.InferenceBinding;
import net.velocity.agent.state.Settlement;
import net.velocity.agent.state.SourceMessage;
import net.velocity.agent.state.TaskPurpose;
import net.velocity.agent.state.UsageAccounting;
import net.velocity.inference.objects.InferenceContent;

/** Versioned encoding and invariants for execution state. */
public final class ExecutionStateCodec {

    record InboxRecord(int formatVersion, String executionId, Correction correction) {}

    record ReceiptRecord(int formatVersion, String executionId, Settlement settlement) {}

    record DiagnosticRecord(
            int formatVersion,
            String executionId,
            String code,
            String raw,
            Instant createdAt) {}

    private static final int VERSION = ExecutionSnapshot.FORMAT_VERSION;
    /** Inbox, receipt and diagnostic records are versioned independently of the snapshot. */
    private static final int RECORD_VERSION = 1;
    /** Includes the snapshot, durable inbox and root receipts; artifacts remain in GridFS. */
    static final int MAX_DOCUMENT_BYTES = 16 * 1024 * 1024;
    static final ObjectMapper MAPPER = mapper();

    private ExecutionStateCodec() {
    }

    private static ObjectMapper mapper() {
        return PersistedJson.builder()
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .addMixIn(InferenceContent.class, ContentTypes.class).build();
    }

    static String encode(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException | IllegalArgumentException e) {
            throw new CorruptStateException("State cannot be encoded", e);
        }
    }

    static <T> T decode(String encoded, Class<T> type) {
        try {
            JsonNode json = MAPPER.readTree(encoded);
            if (json == null || !json.isObject() || !json.has("formatVersion")
                    || !json.get("formatVersion").isIntegralNumber()
                    || !json.get("formatVersion").canConvertToInt()
                    || json.get("formatVersion").intValue() != (type == ExecutionSnapshot.class
                            ? VERSION
                            : RECORD_VERSION)) {
                throw new CorruptStateException("Unsupported or missing state format version");
            }
            if (type == ExecutionSnapshot.class) {
                for (String field : List.of(
                        "purpose",
                        "inference",
                        "providerReservations",
                        "contextDiagnostics")) {
                    if (!json.hasNonNull(field)) {
                        throw new CorruptStateException("Missing snapshot field: " + field);
                    }
                }
                for (String field : List.of(
                        "admissionContext",
                        "messageMetadata",
                        "historyGeneration",
                        "preparedRequests",
                        "completedToolBatches")) {
                    if (!json.hasNonNull(field)) {
                        throw new CorruptStateException("Missing snapshot field: " + field);
                    }
                }
                for (String field : List.of("partialReporting")) {
                    if (!json.has(field)) {
                        throw new CorruptStateException("Missing snapshot field: " + field);
                    }
                }
                JsonNode inference = json.get("inference");
                if (!inference.hasNonNull("binding")) {
                    throw new CorruptStateException("Missing inference field: binding");
                }
                for (String field : List.of("fallback", "transition")) {
                    if (!inference.has(field)) {
                        throw new CorruptStateException("Missing inference field: " + field);
                    }
                }
                JsonNode partial = json.get("partialReporting");
                if (partial != null && !partial.isNull()) {
                    for (String field : List.of("reason", "startedModelTurn", "attempted")) {
                        if (!partial.hasNonNull(field)) {
                            throw new CorruptStateException("Missing partial reporting field: " + field);
                        }
                    }
                }
                JsonNode transition = inference.get("transition");
                JsonNode compact = inference.get("compact");
                if (compact != null && !compact.isNull()) {
                    for (String field : List.of(
                            "sourceRevision",
                            "sourceMessageCount",
                            "sourceHistoryGeneration",
                            "goalGeneration",
                            "messages",
                            "evidenceArtifact")) {
                        if (!compact.hasNonNull(field)) {
                            throw new CorruptStateException("Missing compact view field: " + field);
                        }
                    }
                    validateNativeMessages(compact.get("messages"));
                }
                if (transition != null && !transition.isNull()) {
                    for (String field : List.of(
                            "reason",
                            "sourceRevision",
                            "sourceMessageCount",
                            "sourceHistoryGeneration",
                            "sourceModelTurns",
                            "sourceGoalGeneration",
                            "handoffMessages")) {
                        if (!transition.hasNonNull(field)) {
                            throw new CorruptStateException("Missing transition field: " + field);
                        }
                    }
                    validateNativeMessages(transition.get("handoffMessages"));
                }
                for (String field : List.of(
                        "executionId",
                        "scope",
                        "originalRequest",
                        "currentGoal",
                        "status",
                        "messages",
                        "calls",
                        "corrections",
                        "consumedInboxIds",
                        "pendingQuestions",
                        "blockers",
                        "limits",
                        "createdAt",
                        "updatedAt",
                        "revision",
                        "deliveryTurn",
                        "automaticResumption",
                        "cancellationRequested",
                        "modelTurns",
                        "toolCalls",
                        "totalTokens",
                        "activeMillis",
                        "unusableResponses")) {
                    if (!json.hasNonNull(field)) {
                        throw new CorruptStateException("Missing snapshot field: " + field);
                    }
                }
                JsonNode children = json.get("childRuns");
                if (children != null) {
                    if (!children.isObject()) {
                        throw new CorruptStateException("Invalid child run map");
                    }
                    for (JsonNode child : children) {
                        if (!child.isObject()) {
                            throw new CorruptStateException("Invalid child run");
                        }
                        for (String field : List.of(
                                "id",
                                "rootExecutionId",
                                "parentCallId",
                                "parentCallBinding",
                                "goalGeneration",
                                "profile",
                                "profileVersion",
                                "objective",
                                "references",
                                "createdAt",
                                "updatedAt",
                                "modelRequestInFlight",
                                "reservedTokens",
                                "providerRequests",
                                "requestReservations",
                                "repairAttempts",
                                "resultBytes",
                                "maxStateBytes",
                                "receipts",
                                "status",
                                "messages",
                                "calls",
                                "blockers",
                                "limits",
                                "totalTokens",
                                "inputTokens",
                                "outputTokens",
                                "usageMissing",
                                "usageSources",
                                "activeMillis",
                                "modelTurns",
                                "toolCalls",
                                "unusableResponses")) {
                            if (!child.hasNonNull(field)) {
                                throw new CorruptStateException("Missing child field: " + field);
                            }
                        }
                        validateNativeMessages(child.get("messages"));
                    }
                }
            }
            T value = MAPPER.treeToValue(json, type);
            if (value instanceof ExecutionSnapshot snapshot && snapshot.usageSources != null) {
                UsageAccounting.refreshMissing(snapshot);
                if (snapshot.childRuns != null) {
                    snapshot.childRuns.values().stream().filter(Objects::nonNull)
                            .filter(child -> child.usageSources != null)
                            .forEach(UsageAccounting::refreshMissing);
                }
            }
            return value;
        } catch (IOException | IllegalArgumentException e) {
            throw new CorruptStateException("Corrupt persisted state", e);
        }
    }

    static ExecutionSnapshot copy(ExecutionSnapshot snapshot) {
        try {
            return MAPPER.readValue(encode(snapshot), ExecutionSnapshot.class);
        } catch (IOException e) {
            throw new CorruptStateException("Snapshot cannot be decoded", e);
        }
    }

    static void validate(ExecutionSnapshot state, String id, DelegationProfileRegistry delegationProfiles) {
        if (state.formatVersion != VERSION || state.inference == null
                || state.purpose == null || state.providerReservations == null
                || state.contextDiagnostics == null
                || !id.equals(state.executionId) || state.scope == null
                || state.scope.ownerAccountId() == null
                || state.originalRequest == null || state.originalRequest.isBlank()
                || state.currentGoal == null
                || state.currentGoal.isBlank() || state.status == null
                || state.messages == null || state.calls == null || state.corrections == null
                || state.sourceMessages == null || state.pendingSourceMessages == null
                || state.acceptedSourceTurns == null
                || state.consumedInboxIds == null || state.pendingQuestions == null
                || state.blockers == null
                || state.outputReports == null || state.reportStagingReceipts == null
                || state.goalGenerationInboxIds == null || state.goalGeneration < 1
                || state.limits == null || state.createdAt == null || state.updatedAt == null
                || state.updatedAt.isBefore(state.createdAt) || state.revision < 1 || state.deliveryTurn < 1
                || state.totalTokens < 0 || state.inputTokens < 0 || state.outputTokens < 0
                || state.usageSources == null || state.activeMillis < 0 || state.modelTurns < 0
                || state.toolCalls < 0 || state.unusableResponses < 0 || state.queueSequence < 1) {
            throw new CorruptStateException("Invalid snapshot: " + id);
        }
        if (state.taskOutcome != null && (state.taskOutcome.goalGeneration() != state.goalGeneration
                || state.finalAnswer == null || !state.status.terminal())) {
            throw new CorruptStateException("Task outcome does not match final goal: " + id);
        }
        validateContinuation(state, id);
        var compact = state.inference.compact();
        if (compact != null && (compact.sourceRevision() >= state.revision
                || compact.sourceMessageCount() > state.messages.size()
                || compact.sourceHistoryGeneration() > state.historyGeneration
                || compact.goalGeneration() > state.goalGeneration)) {
            throw new CorruptStateException("Invalid compact request boundary: " + id);
        }
        if (!Objects.equals(state.inference.binding().profileId(), "default")) {
            throw new CorruptStateException("Inference profile does not match task purpose: " + id);
        }
        if (state.admissionContext == null) {
            throw new CorruptStateException("Missing admission context: " + id);
        }
        try {
            net.velocity.agent.state.MessageHistory.validate(state);
        } catch (IllegalArgumentException invalid) {
            throw new CorruptStateException("Invalid message metadata: " + id, invalid);
        }
        if (state.preparedRequests == null || state.completedToolBatches == null
                || state.completedToolBatches.stream()
                        .anyMatch(turn -> turn == null || turn < 1 || turn > state.modelTurns)
                || state.preparedRequests.entrySet().stream().anyMatch(
                        entry -> entry.getKey() < 1
                                || entry.getKey() > state.modelTurns || entry.getValue() == null)) {
            throw new CorruptStateException("Invalid request history: " + id);
        }
        if (state.providerReservations.entrySet().stream().anyMatch(
                entry -> entry.getKey() == null
                        || entry.getKey().isBlank() || entry.getValue() == null || entry.getValue() < 1)) {
            throw new CorruptStateException("Invalid provider reservations: " + id);
        }
        if (state.contextDiagnostics.size() > 24 || state.contextDiagnostics.entrySet().stream().anyMatch(
                entry -> entry.getKey() == null
                        || !Set.of(
                                "message_bytes",
                                "schema_bytes",
                                "instruction_bytes",
                                "envelope_bytes",
                                "estimated_input_tokens",
                                "estimator",
                                "configured_input_allowance",
                                "effective_input_allowance",
                                "output_reserve",
                                "remaining_cumulative_tokens",
                                "profile_id",
                                "provider_id",
                                "model",
                                "decision",
                                "inference_stage",
                                "continuation_outcome",
                                "reporting_mode",
                                "source_estimated_input_tokens",
                                "cloud_estimated_input_tokens").contains(entry.getKey())
                        || !(entry.getValue() instanceof Number || entry.getValue() instanceof String)
                        || entry.getValue() instanceof String text && text.length() > 256)) {
            throw new CorruptStateException("Invalid context diagnostics: " + id);
        }
        validateChildren(state, delegationProfiles);
        if (state.usageSources.entrySet().stream().anyMatch(
                entry -> entry.getKey() == null || entry.getKey().isBlank()
                        || entry.getValue() == null || entry.getValue().inputTokens < 0
                        || entry.getValue().outputTokens < 0 || entry.getValue().totalTokens < 0)) {
            throw new CorruptStateException("Invalid usage accounting: " + id);
        }
        HashSet<String> ids = new HashSet<>();
        var reports = new HashSet<String>();
        if (state.outputReports.size() > 10 || state.outputReports.stream().anyMatch(
                report -> report == null
                        || report.reportId() == null || !reports.add(report.reportId())
                        || report.goalGeneration() != state.goalGeneration
                        || report.filename() == null || report.mediaType() == null
                        || report.checksum() == null || report.byteSize() < 0)) {
            throw new CorruptStateException("Invalid output reports: " + id);
        }
        for (var call : state.calls) {
            if (call == null || call.id == null || call.id.isBlank() || !ids.add(call.id)
                    || (call.name == null || call.name.isBlank())
                            && (call.inputError == null || call.inputError.isBlank())
                    || call.input == null || call.state == null || call.modelTurn < 0
                    || call.modelTurn > state.modelTurns
                    || (call.state == CallState.settled) != (call.settlement != null)) {
                throw new CorruptStateException("Invalid accepted call queue: " + id);
            }
            if (call.settlement != null) {
                validateSettlement(call.settlement);
                if (!call.id.equals(call.settlement.callId())
                        || !Objects.equals(call.binding, call.settlement.binding())) {
                    throw new CorruptStateException("Settlement does not match accepted call: " + id);
                }
            }
        }
        for (int turn : state.completedToolBatches) {
            var batch = state.calls.stream().filter(call -> call.modelTurn == turn).toList();
            if (batch.isEmpty() || batch.stream().anyMatch(call -> call.state != CallState.settled)) {
                throw new CorruptStateException("Completed tool batch is not settled: " + id);
            }
        }
        if (state.sourceTurn != null && (!state.scope.workspaceId().equals(state.sourceTurn.workspaceId())
                || !state.scope.channelId().equals(state.sourceTurn.channelId()))) {
            throw new CorruptStateException("Source turn does not match scope: " + id);
        }
        if (state.monitoring != null && (!"scheduler".equals(state.scope.origin()) || state.sourceTurn == null
                || !state.monitoring.scheduleId().equals(state.sourceTurn.threadId())
                || !state.monitoring.occurrenceId().equals(state.sourceTurn.messageId())
                || !net.velocity.agent.SchedulerIngressGateway.executionId(
                        state.monitoring.scheduleId(),
                        state.monitoring.occurrenceId()).equals(id))) {
            throw new CorruptStateException("Monitoring execution identity mismatch: " + id);
        }
        if (state.sourceMessages.stream().anyMatch(Objects::isNull)
                || state.pendingSourceMessages.stream().anyMatch(Objects::isNull)
                || state.sourceMessages.stream().map(SourceMessage::source).distinct()
                        .count() != state.sourceMessages.size()
                || state.acceptedSourceTurns.stream().anyMatch(Objects::isNull)) {
            throw new CorruptStateException("Invalid source message identities: " + id);
        }
    }

    private static void validateBinding(InferenceBinding binding, String id) {
        try {
            new InferenceBinding(
                    binding.profileId(),
                    binding.profileVersion(),
                    binding.providerId(),
                    binding.model(),
                    binding.inputAllowance(),
                    binding.maxOutputTokens(),
                    binding.safetyMargin());
        } catch (IllegalArgumentException invalid) {
            throw new CorruptStateException("Invalid inference binding: " + id, invalid);
        }
    }

    private static void validateContinuation(ExecutionSnapshot state, String id) {
        // The fallback binding shape (profile and provider) is enforced by the InferenceState record; the
        // task purpose is a cross-group rule.
        if (state.inference.fallback() != null && state.purpose != TaskPurpose.generic) {
            throw new CorruptStateException("Invalid admitted cloud profile: " + id);
        }
        var transition = state.inference.transition();
        if (transition != null && (state.inference.fallback() == null
                || !"llama-cpp".equals(state.inference.binding().providerId())
                || transition.sourceRevision() >= state.revision
                || transition.sourceMessageCount() > state.messages.size()
                || transition.sourceHistoryGeneration() > state.historyGeneration
                || transition.sourceModelTurns() > state.modelTurns
                || transition.sourceGoalGeneration() > state.goalGeneration)) {
            throw new CorruptStateException("Invalid cloud history boundary: " + id);
        }
        // An attempted flag without a mode cannot be represented: the flag lives inside the mode record.
        if (state.partialReporting != null
                && state.modelTurns != state.partialReporting.startedModelTurn()
                        - (state.partialReporting.attempted() ? 0 : 1)
                && !state.partialReporting.retried(state.modelTurns, state.unusableResponses)) {
            throw new CorruptStateException("Invalid partial reporting intent: " + id);
        }
    }

    private static boolean partialReportingAttempted(ExecutionSnapshot state) {
        return state.partialReporting != null && state.partialReporting.attempted();
    }

    /** The mode and its reason and expected model turn are fixed once saved; only the attempt may be added. */
    private static boolean partialReportingChanged(ExecutionSnapshot before, ExecutionSnapshot after) {
        return after.partialReporting == null
                || !Objects.equals(before.partialReporting.reason(), after.partialReporting.reason())
                || before.partialReporting.startedModelTurn() != after.partialReporting.startedModelTurn();
    }

    static void validateInferenceTransition(ExecutionSnapshot before, ExecutionSnapshot after) {
        if (before.formatVersion != after.formatVersion
                || !Objects.equals(before.inference.binding(), after.inference.binding())
                || before.purpose != after.purpose) {
            throw new ConflictException("Inference profile, purpose and admitted policy are immutable");
        }
        if (!Objects.equals(before.admissionContext, after.admissionContext)) {
            throw new ConflictException("Admission context is immutable");
        }
        if (!Objects.equals(before.inference.fallback(), after.inference.fallback())) {
            throw new ConflictException("Admitted cloud continuation policy is immutable");
        }
        if (!Objects.equals(before.inference.compact(), after.inference.compact())) {
            var compact = after.inference.compact();
            if (compact == null || !safeRequestBoundary(before, after)
                    || compact.sourceRevision() != before.revision
                    || compact.sourceMessageCount() != before.messages.size()
                    || compact.sourceHistoryGeneration() != before.historyGeneration
                    || compact.goalGeneration() != before.goalGeneration
                    || !Objects.equals(compact.binding(), before.activeInferenceBinding())) {
                throw new ConflictException("Compaction requires the exact settled root boundary");
            }
        }
        if (before.inference.compact() != null && (after.messages.size() < before.messages.size()
                || !before.messages.equals(after.messages.subList(0, before.messages.size())))) {
            throw new ConflictException("Native history after compaction is append-only");
        }
        if (before.inference.transition() != null) {
            if (!Objects.equals(before.inference.transition(), after.inference.transition())) {
                throw new ConflictException("Cloud transition is one-way and immutable");
            }
            if (after.messages.size() < before.messages.size()
                    || !before.messages.equals(after.messages.subList(0, before.messages.size()))) {
                throw new ConflictException("Native history across a cloud transition is append-only");
            }
        } else if (after.inference.transition() != null) {
            var transition = after.inference.transition();
            if (before.inference.fallback() == null || before.partialReporting != null
                    || after.partialReporting != null
                    || !"llama-cpp".equals(before.inference.binding().providerId())
                    || !safeRequestBoundary(before, after)
                    || transition.sourceRevision() != before.revision
                    || transition.sourceMessageCount() != before.messages.size()
                    || transition.sourceHistoryGeneration() != before.historyGeneration
                    || transition.sourceModelTurns() != before.modelTurns
                    || transition.sourceGoalGeneration() != before.goalGeneration) {
                throw new ConflictException("Cloud transition requires the exact settled root boundary");
            }
        }
        if (before.partialReporting != null && partialReportingChanged(before, after)
                || partialReportingAttempted(before) && !partialReportingAttempted(after)) {
            throw new ConflictException("Partial reporting mode and attempt are immutable");
        }
        if (before.partialReporting == null && after.partialReporting != null
                && (!safeRequestBoundary(before, after) || partialReportingAttempted(after)
                        || after.partialReporting.startedModelTurn() != before.modelTurns + 1)) {
            throw new ConflictException("Partial reporting requires the exact settled root boundary");
        }
        if (!partialReportingAttempted(before) && partialReportingAttempted(after)
                && (before.partialReporting == null
                        || after.modelTurns != before.partialReporting.startedModelTurn())) {
            throw new ConflictException("Partial reporting attempt must match its saved model intent");
        }
        // Only a committed, rejected reporting response permits one more request; an uncertain attempt never does.
        if (partialReportingAttempted(before) && after.modelTurns != before.modelTurns
                && !(before.partialReporting.retryAllowed(before.modelTurns, before.unusableResponses)
                        && after.partialReporting.retried(after.modelTurns, after.unusableResponses))) {
            throw new ConflictException("A partial reporting request cannot be retried");
        }
        if (after.partialReporting != null && after.calls != null
                && after.calls.stream().filter(Objects::nonNull)
                        .anyMatch(
                                call -> call.modelTurn >= after.partialReporting.startedModelTurn()
                                        && call.state == CallState.dispatched)) {
            throw new ConflictException("Partial reporting tools cannot dispatch");
        }
        for (var entry : before.preparedRequests.entrySet()) {
            if (!Objects.equals(after.preparedRequests.get(entry.getKey()), entry.getValue())) {
                throw new ConflictException("Transmitted hook context is immutable");
            }
        }
        validateCallBatchTransition(before, after);
        for (var entry : before.childRuns.entrySet()) {
            var child = after.childRuns.get(entry.getKey());
            if (child != null) {
                validateCallBatchTransition(entry.getValue(), child);
            }
        }
        if (!after.completedToolBatches.containsAll(before.completedToolBatches)) {
            throw new ConflictException("Completed tool batch history is immutable");
        }
    }

    private static boolean safeRequestBoundary(ExecutionSnapshot before, ExecutionSnapshot after) {
        return before.status == net.velocity.agent.state.ExecutionStatus.running
                && after.status == before.status && !before.cancellationRequested
                && !after.cancellationRequested
                && before.pendingQuestions.isEmpty() && before.pendingSourceMessages.isEmpty()
                && before.calls.stream().allMatch(call -> call.state == CallState.settled)
                // A recovered terminal child may retain an unknown request intent and reservation.
                // Actual worker settlement is rechecked by the runner's cancellation handle.
                && before.childRuns.values().stream().allMatch(child -> child.status.terminal())
                && before.revision == after.revision && before.historyGeneration == after.historyGeneration
                && before.messages.equals(after.messages) && before.goalGeneration == after.goalGeneration
                && before.currentGoal.equals(after.currentGoal)
                && before.corrections.equals(after.corrections)
                && before.consumedInboxIds.equals(after.consumedInboxIds)
                && before.pendingSourceMessages.equals(after.pendingSourceMessages)
                && before.pendingQuestions.equals(after.pendingQuestions)
                && encode(before.calls).equals(encode(after.calls))
                && encode(before.childRuns).equals(encode(after.childRuns))
                && before.modelTurns == after.modelTurns && before.toolCalls == after.toolCalls
                && before.totalTokens == after.totalTokens && before.inputTokens == after.inputTokens
                && before.outputTokens == after.outputTokens && before.activeMillis == after.activeMillis
                && before.delegatedModelRequests == after.delegatedModelRequests
                && before.delegatedToolCalls == after.delegatedToolCalls
                && before.delegatedReservedTokens == after.delegatedReservedTokens
                && before.providerReservations.equals(after.providerReservations)
                && before.limits.equals(after.limits);
    }

    static boolean admitsInferenceBoundary(ExecutionSnapshot before, ExecutionSnapshot after) {
        return before.inference.transition() == null && after.inference.transition() != null
                || !Objects.equals(before.inference.compact(), after.inference.compact())
                || before.partialReporting == null && after.partialReporting != null;
    }

    private static void validateCallBatchTransition(ConversationState before, ConversationState after) {
        if (after.calls == null || after.calls.stream().anyMatch(Objects::isNull)) {
            throw new CorruptStateException("Invalid accepted call queue");
        }
        for (var call : before.calls) {
            if (after.calls.stream().filter(candidate -> Objects.equals(candidate.id, call.id))
                    .anyMatch(candidate -> candidate.modelTurn != call.modelTurn)) {
                throw new ConflictException("Accepted tool batch identity is immutable");
            }
        }
    }

    /** Inference constructors permit a few convenience defaults; durable native history does not. */
    private static void validateNativeMessages(JsonNode messages) {
        if (!messages.isArray()) {
            throw new CorruptStateException("Invalid child native messages");
        }
        for (JsonNode message : messages) {
            if (!message.isObject() || !message.path("role").isTextual()
                    || !message.path("content").isArray() || message.path("content").isEmpty()) {
                throw new CorruptStateException("Invalid child native message");
            }
            validateNativeContent(message.get("content"));
        }
    }

    private static void validateNativeContent(JsonNode contents) {
        for (JsonNode block : contents) {
            if (!block.isObject() || !block.path("$kind").isTextual()) {
                throw new CorruptStateException("Invalid child native block");
            }
            switch (block.get("$kind").textValue()) {
                case "text" -> {
                    requireNativeText(block, "text");
                    if (block.has("citations") && !block.get("citations").isArray()) {
                        throw new CorruptStateException("Invalid native citations");
                    }
                }
                case "image" -> {
                    requireNativeText(block, "base64");
                    requireNativeText(block, "mediaType");
                }
                case "thinking" -> {
                    requireNativeText(block, "text");
                    requireNativeText(block, "signature");
                }
                case "tool_call" -> {
                    requireNativeText(block, "id");
                    requireNativeText(block, "name");
                    if (!block.path("input").isObject()) {
                        throw new CorruptStateException("Invalid native tool input");
                    }
                }
                case "tool_result" -> {
                    requireNativeText(block, "toolUseId");
                    if (!block.path("content").isArray() || block.path("content").isEmpty()
                            || !block.path("error").isBoolean()) {
                        throw new CorruptStateException("Invalid native tool result");
                    }
                    validateNativeContent(block.get("content"));
                }
                case "provider_block" -> {
                    requireNativeText(block, "provider");
                    requireNativeText(block, "type");
                    if (!block.path("data").isObject()) {
                        throw new CorruptStateException("Invalid native provider block");
                    }
                }
                default -> throw new CorruptStateException("Unknown native block type");
            }
        }
    }

    private static void requireNativeText(JsonNode block, String field) {
        if (!block.path(field).isTextual()) {
            throw new CorruptStateException("Invalid native block " + field);
        }
    }

    private static void validateChildren(
            ExecutionSnapshot root,
            DelegationProfileRegistry delegationProfiles) {
        if (root.childRuns == null || root.maxChildRuns < 1 || root.maxChildStateBytes < 1
                || root.childRuns.size() > root.maxChildRuns || root.delegatedModelRequests < 0
                || root.delegatedToolCalls < 0 || root.delegatedReservedTokens < 0) {
            throw new CorruptStateException("Invalid child run allowance");
        }
        int active = 0;
        long rootReservations = 0;
        int rootProviderRequests = 0;
        int rootToolCalls = 0;
        for (var entry : root.childRuns.entrySet()) {
            ChildRun child = entry.getValue();
            if (child == null || child.id == null || !Objects.equals(entry.getKey(), child.id)
                    || !root.executionId.equals(child.rootExecutionId) || child.parentCallId == null
                    || child.parentCallBinding == null || child.parentCallBinding.isBlank()
                    || child.goalGeneration < 1
                    || child.goalGeneration > root.goalGeneration
                    || !child.id.equals(
                            ChildRun.id(
                                    root.executionId,
                                    child.parentCallId,
                                    child.parentCallBinding,
                                    child.goalGeneration))
                    || !delegationProfiles.known(child.profile, child.profileVersion)
                    || child.objective == null || child.objective.isBlank()
                    || child.references == null || child.references.stream().anyMatch(Objects::isNull)
                    || child.createdAt == null || child.updatedAt == null
                    || child.updatedAt.isBefore(child.createdAt)
                    || child.reservedTokens < 0 || child.repairAttempts < 0 || child.repairAttempts > 1
                    || child.resultBytes < 1
                    || child.maxStateBytes < 1 || child.receipts == null) {
                throw new CorruptStateException("Invalid child run identity or bounds: " + entry.getKey());
            }
            if (child.providerRequests < 0 || child.requestReservations == null
                    || child.requestReservations.entrySet().stream().anyMatch(
                            item -> item.getKey() == null
                                    || item.getKey().isBlank() || item.getValue() == null
                                    || item.getValue() < 1)
                    || child.requestReservations.size() > child.providerRequests
                    || child.reservedTokens != child.requestReservations.values().stream()
                            .mapToLong(Long::longValue).sum()) {
                throw new CorruptStateException("Invalid child provider request reservations");
            }
            rootReservations = Math.addExact(rootReservations, child.reservedTokens);
            rootProviderRequests = Math.addExact(rootProviderRequests, child.providerRequests);
            rootToolCalls = Math.addExact(rootToolCalls, child.toolCalls);
            validateConversation(child, child.id);
            if (child.inferenceBinding == null) {
                throw new CorruptStateException("Missing child native inference identity");
            }
            if (child.inferenceBinding != null) {
                validateBinding(child.inferenceBinding, child.id);
                if (!Objects.equals(child.inferenceBinding, root.inference.binding())
                        && !Objects.equals(child.inferenceBinding, root.inference.fallback())) {
                    throw new CorruptStateException("Child inference identity was not admitted by its root");
                }
            }
            if (child.modelRequestInFlight != (child.activeUsageSource != null)
                    || child.modelRequestInFlight && (!child.activeUsageSource.matches("model:[1-9][0-9]*")
                            || !child.usageSources.containsKey(child.activeUsageSource))) {
                throw new CorruptStateException("Invalid child provider request intent");
            }
            boolean cumulativeLimits = !root.usesTaskDeadlineOnly();
            if (child.modelTurns > child.providerRequests
                    || cumulativeLimits && (child.providerRequests > child.limits.modelTurns()
                            || child.toolCalls > child.limits.toolCalls())) {
                throw new CorruptStateException("Invalid child limits or request counters");
            }
            if (child.status == net.velocity.agent.state.ExecutionStatus.waiting_for_user) {
                throw new CorruptStateException("A child cannot wait for user input");
            }
            if (!child.status.terminal() && ++active > 1) {
                throw new CorruptStateException("Only one active child is permitted");
            }
            if (child.limits.activeMillis() > root.limits.activeMillis()
                    || cumulativeLimits && (child.limits.totalTokens() > root.limits.totalTokens()
                            || child.limits.modelTurns() > root.limits.modelTurns()
                            || child.limits.toolCalls() > root.limits.toolCalls()
                            || child.limits.toolTimeoutMillis() > root.limits.toolTimeoutMillis())
                    || child.limits.outputPreviewChars() > root.limits.outputPreviewChars()) {
                throw new CorruptStateException("Child limits exceed root allowances");
            }
            var wrapper = root.calls.stream()
                    .filter(call -> call != null && child.parentCallId.equals(call.id)).findFirst()
                    .orElseThrow(() -> new CorruptStateException("Child has no parent call"));
            if (!"delegate_work".equals(wrapper.name) || !child.parentCallBinding.equals(wrapper.binding)
                    || wrapper.goalGeneration != child.goalGeneration || wrapper.state == null
                    || wrapper.state == CallState.accepted
                    || wrapper.input == null
                    || !Objects.equals(
                            wrapper.binding,
                            AuthorizationPolicy.binding(root.scope, wrapper.id, wrapper.name, wrapper.input))
                    || !child.profile.equals(wrapper.input.get("profile"))
                    || !child.objective.equals(wrapper.input.get("objective"))
                    || !Objects.equals(child.context, wrapper.input.get("context"))
                    || !child.references.equals(wrapper.input.getOrDefault("references", List.of()))) {
                throw new CorruptStateException("Child parent call association mismatch");
            }
            for (var reference : child.references) {
                if (!Set.of("kind", "content_hash", "id", "reference").containsAll(reference.keySet())
                        || !(reference.get("kind") instanceof String kind)
                        || !Set.of("document", "evidence").contains(kind)
                        || !reference.containsKey("id") || !reference.containsKey("content_hash")
                        || reference.values().stream().anyMatch(
                                value -> !(value instanceof String text)
                                        || text.isBlank() || text.length() > 128)) {
                    throw new CorruptStateException("Invalid typed child reference");
                }
            }
            var ids = new HashSet<String>();
            for (var call : child.calls) {
                if (call == null || call.id == null || call.id.isBlank() || !ids.add(call.id)
                        || call.input == null
                        || call.state == null || call.modelTurn < 0 || call.modelTurn > child.modelTurns
                        || (call.name == null || call.name.isBlank())
                                && (call.inputError == null || call.inputError.isBlank())
                        || (call.state == CallState.settled) != (call.settlement != null)
                        || call.goalGeneration != child.goalGeneration) {
                    throw new CorruptStateException("Invalid child accepted call queue");
                }
                if (call.name != null && !call.name.isBlank() && !Objects.equals(
                        call.binding,
                        AuthorizationPolicy
                                .binding(root.scope, child.id + ":" + call.id, call.name, call.input))) {
                    throw new CorruptStateException("Child call binding mismatch");
                }
                if (call.settlement != null && !call.settlement.equals(child.receipts.get(call.id))) {
                    throw new CorruptStateException("Child settlement is missing its matching receipt");
                }
            }
            for (var receipt : child.receipts.entrySet()) {
                validateSettlement(receipt.getValue());
                var call = child.calls.stream().filter(item -> Objects.equals(item.id, receipt.getKey()))
                        .findFirst()
                        .orElseThrow(() -> new CorruptStateException("Child receipt has no accepted call"));
                if (!Objects.equals(receipt.getKey(), receipt.getValue().callId())
                        || !Objects.equals(call.binding, receipt.getValue().binding())) {
                    throw new CorruptStateException("Child receipt binding mismatch");
                }
            }
            if (child.result != null
                    && child.result.getBytes(StandardCharsets.UTF_8).length > child.resultBytes) {
                throw new CorruptStateException("Child result exceeds its persisted allowance");
            }
            if (child.result != null) {
                validateChildResult(child);
            }
            if (bytes(child) > child.maxStateBytes) {
                throw new CorruptStateException("Child state byte allowance exceeded");
            }
        }
        if (bytes(root.childRuns) > root.maxChildStateBytes) {
            throw new CorruptStateException("Aggregate child state byte allowance exceeded");
        }
        if (root.delegatedReservedTokens != rootReservations
                || root.delegatedModelRequests != rootProviderRequests
                || root.delegatedToolCalls != rootToolCalls) {
            throw new CorruptStateException("Root delegated accounting does not match child requests");
        }
    }

    private static void validateChildResult(ChildRun child) {
        try {
            JsonNode result = MAPPER.readTree(child.result);
            if (!child.status.terminal() || result == null || !result.isObject()
                    || !result.path("run_id").isTextual()
                    || !child.id.equals(result.path("run_id").textValue())
                    || !result.path("status").isTextual()
                    || !Set.of(
                            "complete",
                            "partial",
                            "needs_input",
                            "failed",
                            "unavailable",
                            "superseded",
                            "interrupted",
                            "cancelled")
                            .contains(result.path("status").textValue())
                    || !"untrusted_data".equals(result.path("content_trust").textValue())
                    || !result.path("summary").isTextual()
                    || result.path("summary").textValue().length() > 2048
                    || result.has("profile") && !child.profile.equals(result.path("profile").textValue())
                    || result.has("profile_version") && (!result.path("profile_version").isIntegralNumber()
                            || !result.path("profile_version").canConvertToInt()
                            || result.path("profile_version").intValue() != child.profileVersion)) {
                throw new CorruptStateException("Invalid persisted child result metadata");
            }
        } catch (IOException invalid) {
            throw new CorruptStateException("Invalid persisted child result JSON", invalid);
        }
    }

    private static void validateConversation(ConversationState state, String id) {
        if (state.messages == null || state.messages.stream().anyMatch(Objects::isNull) || state.calls == null
                || state.blockers == null || state.blockers.stream().anyMatch(Objects::isNull)
                || state.status == null || state.limits == null || state.totalTokens < 0
                || state.inputTokens < 0
                || state.outputTokens < 0 || state.usageSources == null || state.activeMillis < 0
                || state.modelTurns < 0 || state.toolCalls < 0 || state.unusableResponses < 0) {
            throw new CorruptStateException("Invalid conversation state: " + id);
        }
        if (state.usageSources.entrySet().stream().anyMatch(
                entry -> entry.getKey() == null || entry.getKey().isBlank()
                        || entry.getValue() == null || entry.getValue().inputTokens < 0
                        || entry.getValue().outputTokens < 0 || entry.getValue().totalTokens < 0)) {
            throw new CorruptStateException("Invalid conversation usage: " + id);
        }
    }

    /** Association, effective allowances and receipt ordering are repository-enforced contracts. */
    static void validateChildTransition(ExecutionSnapshot before, ExecutionSnapshot after) {
        if (after.childRuns == null) {
            throw new CorruptStateException("Missing child runs");
        }
        if (after.maxChildRuns > before.maxChildRuns
                || after.maxChildStateBytes > before.maxChildStateBytes) {
            throw new ConflictException("Child allowances cannot increase");
        }
        for (var entry : before.childRuns.entrySet()) {
            ChildRun old = entry.getValue();
            ChildRun child = after.childRuns.get(entry.getKey());
            if (child == null || !Objects.equals(old.id, child.id)
                    || !Objects.equals(old.rootExecutionId, child.rootExecutionId)
                    || !Objects.equals(old.parentCallId, child.parentCallId)
                    || !Objects.equals(old.parentCallBinding, child.parentCallBinding)
                    || old.goalGeneration != child.goalGeneration
                    || !Objects.equals(old.profile, child.profile)
                    || old.profileVersion != child.profileVersion
                    || !Objects.equals(old.objective, child.objective)
                    || !Objects.equals(old.inferenceBinding, child.inferenceBinding)
                    || !Objects.equals(old.references, child.references)
                    || !Objects.equals(old.context, child.context)
                    || !Objects.equals(old.createdAt, child.createdAt)
                    || old.resultBytes != child.resultBytes || old.maxStateBytes != child.maxStateBytes
                    || !Objects.equals(old.limits, child.limits)) {
                throw new ConflictException("Child identity and admitted allowances are immutable");
            }
            if (child.calls == null || child.calls.stream().anyMatch(Objects::isNull)
                    || child.updatedAt == null || child.status == null || child.messages == null) {
                throw new CorruptStateException("Invalid child checkpoint");
            }
            if (child.messages.size() < old.messages.size()
                    || !old.messages.equals(child.messages.subList(0, old.messages.size()))) {
                throw new ConflictException("Persisted child native history is append-only");
            }
            if (child.calls.size() < old.calls.size() || !old.calls.stream().map(call -> call.id).toList()
                    .equals(
                            child.calls.subList(0, old.calls.size()).stream().map(call -> call.id)
                                    .toList())) {
                throw new ConflictException("Child accepted call order is immutable");
            }
            if (old.status.terminal() && !child.status.terminal()
                    && !((old.status == net.velocity.agent.state.ExecutionStatus.succeeded
                            || old.status == net.velocity.agent.state.ExecutionStatus.partial
                                    && old.failure == null && old.finalAnswer != null)
                            && old.result == null
                            && old.repairAttempts == 0 && child.repairAttempts == 1
                            && child.status == net.velocity.agent.state.ExecutionStatus.running)) {
                throw new ConflictException("Terminal child cannot resume");
            }
            if (old.result != null && !Objects.equals(old.result, child.result)) {
                throw new ConflictException("A published child result is immutable");
            }
            if (child.receipts == null || old.receipts.entrySet().stream()
                    .anyMatch(
                            receipt -> !Objects
                                    .equals(receipt.getValue(), child.receipts.get(receipt.getKey())))) {
                throw new ConflictException("Child receipts are immutable");
            }
            if (child.updatedAt.isBefore(old.updatedAt) || child.providerRequests < old.providerRequests
                    || child.repairAttempts < old.repairAttempts) {
                throw new ConflictException("Child checkpoints cannot regress");
            }
            if (child.modelTurns < old.modelTurns || child.toolCalls < old.toolCalls
                    || child.totalTokens < old.totalTokens
                    || child.inputTokens < old.inputTokens || child.outputTokens < old.outputTokens
                    || child.activeMillis < old.activeMillis) {
                throw new ConflictException("Child consumed allowances cannot decrease");
            }
            for (var prior : old.calls) {
                var accepted = child.calls.stream().filter(call -> Objects.equals(call.id, prior.id))
                        .findFirst()
                        .orElseThrow(() -> new ConflictException("Child accepted calls cannot be removed"));
                if (!Objects.equals(prior.binding, accepted.binding)
                        || !Objects.equals(prior.name, accepted.name)
                        || !Objects.equals(prior.input, accepted.input)
                        || !Objects.equals(prior.toolset, accepted.toolset)
                        || prior.goalGeneration != accepted.goalGeneration) {
                    throw new ConflictException("Child accepted call identity is immutable");
                }
            }
            for (var call : child.calls) {
                if (call.state == CallState.settled
                        && !Objects.equals(call.settlement, old.receipts.get(call.id))) {
                    throw new ConflictException("Confirmed child receipt must precede snapshot settlement");
                }
            }
        }
        for (var entry : after.childRuns.entrySet()) {
            if (!before.childRuns.containsKey(entry.getKey())) {
                ChildRun child = entry.getValue();
                if (before.status != net.velocity.agent.state.ExecutionStatus.running
                        || before.cancellationRequested
                        || before.monitoring != null || child == null || child.calls == null
                        || child.receipts == null
                        || child.goalGeneration != before.goalGeneration
                        || !child.calls.isEmpty() || !child.receipts.isEmpty()) {
                    throw new ConflictException("Child admission requires a current running root");
                }
                var wrapper = before.calls.stream()
                        .filter(call -> Objects.equals(call.id, child.parentCallId)).findFirst();
                if (wrapper.isEmpty() || wrapper.get().state != CallState.dispatched) {
                    throw new ConflictException("Child admission must follow a persisted wrapper dispatch");
                }
                if (!Objects.equals(child.inferenceBinding, before.activeInferenceBinding())) {
                    throw new ConflictException("New children must pin the active root inference binding");
                }
            }
        }
    }

    static int bytes(Object value) {
        return encode(value).getBytes(StandardCharsets.UTF_8).length;
    }

    public static int encodedBytes(Object value) {
        return bytes(value);
    }

    static boolean admitsChild(ExecutionSnapshot before, ExecutionSnapshot after) {
        return after.childRuns.keySet().stream().anyMatch(id -> !before.childRuns.containsKey(id));
    }

    static void validateDocumentSize(Document document) {
        if (new RawBsonDocument(document, new DocumentCodec()).getByteBuffer()
                .remaining() > MAX_DOCUMENT_BYTES) {
            throw new CorruptStateException(
                    "Execution snapshot, inbox and receipts exceed the 16 MiB BSON limit");
        }
    }

    static void validateSettlement(Settlement settlement) {
        if (settlement == null || settlement.callId() == null || settlement.callId().isBlank()
                || settlement.outcome() == null || settlement.outcome().isBlank()
                || settlement.confirmedAt() == null
                || settlement.preview() == null) {
            throw new CorruptStateException("Invalid settlement receipt");
        }
    }

    static void revision(ExecutionSnapshot state, long expected) {
        if (expected < -1 || expected >= 0 && expected != state.revision) {
            throw new ConflictException("Stale execution revision");
        }
    }

    static String name(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
                || value.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])")) {
            throw new IllegalArgumentException("Invalid execution or record identifier");
        }
        return value;
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "$kind")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = InferenceContent.Text.class, name = "text"),
            @JsonSubTypes.Type(value = InferenceContent.Image.class, name = "image"),
            @JsonSubTypes.Type(value = InferenceContent.Thinking.class, name = "thinking"),
            @JsonSubTypes.Type(value = InferenceContent.ToolCall.class, name = "tool_call"),
            @JsonSubTypes.Type(value = InferenceContent.ToolResult.class, name = "tool_result"),
            @JsonSubTypes.Type(value = InferenceContent.ProviderBlock.class, name = "provider_block")
    })
    private abstract static class ContentTypes {}
}
