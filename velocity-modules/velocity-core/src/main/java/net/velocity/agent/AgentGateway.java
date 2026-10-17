package net.velocity.agent;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.velocity.agent.config.AgentConcurrencyProperties;
import net.velocity.agent.config.DelegationProperties;
import net.velocity.agent.delegation.ChildContextHooks;
import net.velocity.agent.delegation.ChildReferenceValidator;
import net.velocity.agent.delegation.DelegationProfileRegistry;
import net.velocity.agent.persistence.*;
import net.velocity.agent.state.*;
import net.velocity.inference.InferenceProfileRegistry;
import net.velocity.inference.SystemPrompts;
import net.velocity.inference.objects.*;

/**
 * Shared admission and lifecycle boundary for trusted HTTP, Slack and scheduler adapters. Admission, control
 * (steering, answers, approvals, cancellation, deletion) and startup recovery live here; the root FIFO and
 * its workers are {@link RootQueue}, and the trusted verifiers of saved authority are
 * {@link ExecutionAuthority}. Every public method that touches task state is synchronized on this gateway,
 * which is also the lock the queue uses.
 */
public final class AgentGateway implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AgentGateway.class);
    private final ExecutionRepository repository;
    private final ModelClient model;
    private final InferenceProfileRegistry profiles;
    private final ToolRegistry tools;
    private final ContextHooks hooks;
    private final AttachmentStore attachments;
    private final ExecutionLimits serverLimits;
    private final boolean automaticResumption;
    private final DelegationProperties delegation;
    private final ConversationRunner runner;
    private final ToolCallExecutor toolCalls;
    private final ConversationContext conversation;
    private final RootQueue queue;
    private final ExecutionAuthority authority = new ExecutionAuthority();
    private final List<Consumer<String>> deletionListeners = new CopyOnWriteArrayList<>();
    /** External I/O must settle and reject future dispatch before any owned content is removed. */
    private final List<Consumer<String>> deletionBarriers = new CopyOnWriteArrayList<>();

    private java.util.function.Function<TrustedScope, String> admissionLearning = scope -> "";
    private Consumer<TrustedScope> foregroundStarted = scope -> {};
    private Consumer<TrustedScope> foregroundSettled = scope -> {};
    private Consumer<ExecutionSnapshot> deliveryAcknowledged = snapshot -> {};

    public AgentGateway(
            ExecutionRepository repository,
            ModelClient model,
            ToolRegistry tools,
            AuthorizationPolicy authorization,
            ContextHooks hooks,
            ExecutionLimits serverLimits,
            long taskTokenCeiling,
            boolean automaticResumption,
            DelegationProperties delegation,
            ChildContextHooks childHooks,
            ChildReferenceValidator references,
            AttachmentStore attachments,
            InferenceProfileRegistry profiles,
            DelegationProfileRegistry delegationProfiles,
            AgentConcurrencyProperties concurrency) {
        this.repository = Objects.requireNonNull(repository);
        this.model = Objects.requireNonNull(model);
        this.profiles = profiles;
        this.tools = Objects.requireNonNull(tools);
        this.hooks = Objects.requireNonNull(hooks);
        this.attachments = Objects.requireNonNull(attachments);
        this.serverLimits = Objects.requireNonNull(serverLimits);
        this.automaticResumption = automaticResumption;
        this.delegation = Objects.requireNonNull(delegation);
        this.conversation = new ConversationContext(tools, hooks, delegationProfiles);
        this.queue = new RootQueue(this, repository, concurrency, new QueueRuntime());
        var progress = new ExecutionProgress(
                repository,
                model::outputTokenAllowance,
                queue::externallySettled,
                taskTokenCeiling);
        var delegationService = new DelegationService(
                repository,
                model,
                tools,
                authorization,
                delegation,
                childHooks,
                references,
                profiles,
                delegationProfiles,
                conversation,
                taskTokenCeiling);
        this.toolCalls = new ToolCallExecutor(repository, tools, authorization, progress, delegationService);
        this.runner = new ConversationRunner(
                repository,
                model,
                hooks,
                profiles,
                conversation,
                progress,
                toolCalls);
        queue.start();
    }

    public void setAccountValidator(Consumer<TrustedScope> validator) {
        authority.setAccount(validator);
    }

    public void setScheduledValidator(Consumer<ExecutionSnapshot> validator) {
        authority.setScheduled(validator);
        toolCalls.setScheduledValidator(validator);
    }

    /** Trusted monitoring integration verifies the complete saved association before scoped reads. */
    public void setMonitoringValidator(Consumer<ExecutionSnapshot> validator) {
        authority.setMonitoring(validator);
        toolCalls.setMonitoringValidator(validator);
    }

    /** Trusted admission extensions run once; replay and steering never reload mutable knowledge. */
    public void setAdmissionLearning(
            java.util.function.Function<TrustedScope, String> context,
            Consumer<TrustedScope> started,
            Consumer<TrustedScope> settled,
            Consumer<ExecutionSnapshot> delivered) {
        admissionLearning = Objects.requireNonNull(context);
        foregroundStarted = Objects.requireNonNull(started);
        foregroundSettled = Objects.requireNonNull(settled);
        deliveryAcknowledged = Objects.requireNonNull(delivered);
    }

    /** Called only after all destination delivery operations have durable acknowledgement. */
    public void recordAcknowledgedDelivery(ExecutionSnapshot snapshot) {
        try {
            deliveryAcknowledged.accept(snapshot);
        } catch (RuntimeException failure) {
            log.warn("executionId={} stage=learning outcome=handoff_pending", snapshot.executionId);
            throw failure;
        }
    }

    public boolean available() {
        return !queue.closing() && profiles != null && profiles.available(InferenceProfileRegistry.DEFAULT);
    }

    /** True once shutdown began draining workers; admission is refused from then on. */
    public synchronized boolean closing() {
        return queue.closing();
    }

    /** Root tasks that loaded a skill get one extra turn to patch it before their answer is accepted. */
    public void setSkillSelfCheck(boolean enabled) {
        runner.setSkillSelfCheck(enabled);
    }

    /** Trusted registration metadata decides which accepted calls wait for the requester's approval. */
    public void setApprovalPolicy(ToolApprovalPolicy policy) {
        toolCalls.setApprovalPolicy(Objects.requireNonNull(policy));
    }

    /** Live console output: saved changes are published by the repository; model text by the runner. */
    public void setLiveOutput(ExecutionEvents events, boolean modelStreaming) {
        runner.setLiveOutput(events, modelStreaming);
        if (events != null) {
            deletionListeners.add(events::forget);
        }
    }

    /**
     * Records the requester's decision on calls held for approval. A null call ID decides every pending
     * call at once (Slack replies cannot name one). The task resumes when no pending approval remains;
     * a declined call settles without running when the worker next processes the queue.
     */
    public synchronized ExecutionSnapshot approve(
            String id,
            String callId,
            boolean approved,
            Long expectedRevision,
            TrustedScope actor,
            SourceTurn turn) {
        var existing = get(id);
        requireOwner(existing, actor);
        sourceContext(existing, turn, List.of());
        if (hasSourceTurn(existing, turn)) {
            return resumePending(existing);
        }
        queue.requireQueueCapacity(id);
        Instant now = Instant.now();
        repository.update(id, expectedRevision == null ? -1 : expectedRevision, s -> {
            if (s.status != ExecutionStatus.waiting_for_user) {
                throw new ConflictException("Execution is not waiting for input");
            }
            var pending = s.calls.stream().filter(AcceptedCall::awaitingApproval)
                    .filter(call -> callId == null || call.id.equals(callId)).toList();
            if (pending.isEmpty()) {
                throw new ConflictException("No call is awaiting approval");
            }
            for (var call : pending) {
                call.approval = call.approval.decided(approved, now, actor.origin());
            }
            if (turn != null) {
                s.acceptedSourceTurns.add(turn);
            }
            if (s.calls.stream().noneMatch(AcceptedCall::awaitingApproval)) {
                s.status = ExecutionStatus.running;
                s.deliveryTurn++;
            }
        });
        queue.launch(id);
        return get(id);
    }

    /**
     * Persist admission before launching; source-turn checking is local-process
     * serialization.
     */
    public synchronized ExecutionSnapshot create(StartRequest request) {
        return admit(request, null);
    }

    /** Trusted scheduler admission: a durable occurrence keeps the same ID across crash recovery. */
    public synchronized ExecutionSnapshot createScheduled(String executionId, StartRequest request) {
        if (!"scheduler".equals(request.scope().origin()) || request.sourceTurn() == null) {
            throw new IllegalArgumentException("Scheduled admission requires trusted occurrence identity");
        }
        UUID.fromString(executionId);
        try {
            var existing = repository.get(executionId);
            authority.verifyAccount(existing.scope);
            authority.scheduled().accept(existing);
            if (!existing.scope.equals(request.scope())
                    || !Objects.equals(existing.sourceTurn, request.sourceTurn())
                    || !Objects.equals(existing.monitoring, request.monitoring())
                    || !existing.originalRequest.equals(request.request())) {
                throw new ConflictException("Scheduled execution identity mismatch");
            }
            return existing;
        } catch (NotFoundException absent) {
            // Repository tombstones still reject admission of a deleted execution ID.
            return admit(request, executionId);
        }
    }

    /**
     * Admits a new task that continues a stopped one, seeded with the source's saved progress. The source
     * must be owned by the actor, terminal without a finished response, and settled, so no external work
     * from it can still be running. Each source has at most one live continuation: a repeated request
     * returns it, and a stopped continuation is continued in turn.
     */
    public synchronized ExecutionSnapshot continueTask(
            String sourceId,
            String instruction,
            TrustedScope actor) {
        var source = repository.get(sourceId);
        requireOwner(source, actor);
        if (!continuable(source)) {
            throw new ConflictException(
                    "Only stopped partial, failed, blocked or cancelled tasks can be continued");
        }
        var existing = repository.continuationOf(sourceId);
        if (existing.isPresent()) {
            return get(existing.get());
        }
        if (!settled(sourceId)) {
            throw new ConflictException("Execution is not settled");
        }
        var seed = TaskContinuation.prepare(source, instruction);
        // The source's files are copied into the continuation, which owns its own artifacts.
        return admit(
                new StartRequest(
                        seed.request(),
                        source.conversationId,
                        actor,
                        null,
                        List.of(),
                        List.of(new InferenceContent.Text(seed.handoff())),
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        attachments.stored(sourceId, actor)),
                null,
                sourceId);
    }

    /** Lifecycle eligibility only; ownership, settlement and an existing continuation are checked separately. */
    public static boolean continuable(ExecutionSnapshot snapshot) {
        return TaskContinuation.CONTINUABLE.contains(snapshot.status) && snapshot.monitoring == null
                && snapshot.purpose == TaskPurpose.generic;
    }

    public Optional<String> continuationOf(String sourceId) {
        return repository.continuationOf(sourceId);
    }

    private ExecutionSnapshot admit(StartRequest request, String executionId) {
        return admit(request, executionId, null);
    }

    private ExecutionSnapshot admit(StartRequest request, String executionId, String continuedFrom) {
        authority.verifyAccount(request.scope());
        if (!available()) {
            throw new IllegalStateException("Agent inference is unavailable");
        }
        var limits = request.monitoring() == null
                ? serverLimits.reduce(request.limits()).taskTimeOnly()
                : monitoringLimits(request.limits());
        if (request.sourceTurn() != null) {
            var duplicate = repository.list().stream().filter(s -> hasSourceTurn(s, request.sourceTurn()))
                    .findFirst();
            if (duplicate.isPresent()) {
                if (!duplicate.get().scope.equals(request.scope())) {
                    throw new SecurityException("Source turn is already bound to a different authority");
                }
                if (executionId != null && !executionId.equals(duplicate.get().executionId)) {
                    throw new ConflictException(
                            "Scheduled source turn is already bound to a different execution");
                }
                return duplicate.get();
            }
        }
        queue.requireQueueCapacity(null);
        var state = new ExecutionSnapshot();
        state.formatVersion = ExecutionSnapshot.FORMAT_VERSION;
        var binding = defaultBinding();
        state.inference = new InferenceState(
                binding,
                profiles == null || !"llama-cpp".equals(binding.providerId())
                        ? null
                        : profiles.cloudFallback().map(InferenceBinding::of).orElse(null),
                null,
                null);
        state.executionId = executionId == null ? UUID.randomUUID().toString() : executionId;
        // Uploads are durable under the new ID before the snapshot exists; a failed admission discards them.
        List<AttachmentStore.Stored> uploads = attachments
                .store(state.executionId, request.scope(), request.uploads());
        ExecutionSnapshot persisted;
        try {
            persisted = persist(request, state, limits, continuedFrom, uploads);
        } catch (RuntimeException failure) {
            if (!uploads.isEmpty()) {
                attachments.discard(state.executionId);
            }
            throw failure;
        }
        AgentMetrics.taskAdmitted(persisted.scope.origin(), persisted.monitoring != null);
        queue.launch(persisted.executionId);
        return get(persisted.executionId);
    }

    private ExecutionSnapshot persist(
            StartRequest request,
            ExecutionSnapshot state,
            ExecutionLimits limits,
            String continuedFrom,
            List<AttachmentStore.Stored> uploads) {
        state.queueSequence = queue.nextQueueSequence();
        state.queuedAt = Instant.now();
        state.conversationId = request.conversationId() == null || request.conversationId().isBlank()
                ? state.executionId
                : request.conversationId();
        state.originalRequest = request.request();
        state.currentGoal = request.request();
        state.scope = request.scope();
        state.sourceTurn = request.sourceTurn();
        state.continuedFrom = continuedFrom;
        state.monitoring = request.monitoring();
        authority.verifyAssociations(state);
        state.limits = limits;
        state.maxChildRuns = delegation.maxChildren();
        state.maxChildStateBytes = delegation.maxAggregateStateBytes();
        state.instructions = request.instructions();
        state.automaticResumption = request.automaticResumption() == null
                ? automaticResumption
                : request.automaticResumption();
        state.messages.addAll(request.history());
        var attached = new ArrayList<InferenceContent>(request.attachments());
        if (!uploads.isEmpty()) {
            attached.add(new InferenceContent.Text(uploadsNotice(uploads)));
        }
        if (request.sourceMessages().isEmpty()) {
            var content = new ArrayList<InferenceContent>();
            content.add(new InferenceContent.Text(request.request()));
            content.addAll(attached);
            state.messages.add(new InferenceMessage(InferenceMessage.Role.USER, content));
        } else {
            SourceContext.append(state, request.sourceMessages());
            if (!attached.isEmpty()) {
                var content = new ArrayList<>(state.messages.getLast().content());
                content.addAll(attached);
                state.messages.set(
                        state.messages.size() - 1,
                        new InferenceMessage(InferenceMessage.Role.USER, content));
            }
        }
        if (request.sourceTurn() != null) {
            state.acceptedSourceTurns.add(request.sourceTurn());
        }
        String learning = "";
        if (state.monitoring == null) {
            learning = admissionLearning.apply(state.scope);
        }
        state.admissionContext = conversation.freezeAdmission(state, learning);
        return repository.create(state);
    }

    /** Metadata only: the files' contents reach the conversation solely through tool results. */
    private static String uploadsNotice(List<AttachmentStore.Stored> uploads) {
        var notice = new StringBuilder(SystemPrompts.TASK_FILES_NOTICE);
        for (var upload : uploads) {
            notice.append("\n- ").append(upload.filename()).append(" (").append(upload.mediaType())
                    .append(", ").append(upload.byteSize()).append(" bytes) file_id=").append(upload.id());
        }
        return notice.toString();
    }

    private InferenceBinding defaultBinding() {
        return InferenceBinding.of(profiles.require(InferenceProfileRegistry.DEFAULT));
    }

    private ExecutionLimits monitoringLimits(ExecutionLimits requested) {
        Objects.requireNonNull(requested, "Monitoring executions require explicit bounded limits");
        return new ExecutionLimits(
                Math.min(serverLimits.totalTokens(), requested.totalTokens()),
                Math.min(serverLimits.activeMillis(), requested.activeMillis()),
                Math.min(serverLimits.modelTurns(), requested.modelTurns()),
                Math.min(serverLimits.toolCalls(), requested.toolCalls()),
                Math.min(serverLimits.toolTimeoutMillis(), requested.toolTimeoutMillis()),
                Math.min(serverLimits.outputPreviewChars(), requested.outputPreviewChars()),
                // Server limits carry no output reserve; the pinned profile's maximum output bounds this one.
                requested.outputReserve());
    }

    /** All entry points share this durable FIFO. Children execute inside the admitted root's slot. */
    public synchronized void launch(String id) {
        queue.launch(id);
    }

    public record ConcurrencyStatus(
            int activeRoots,
            int queuedRoots,
            int maxActiveRoots,
            int queueCapacity) {}

    public synchronized ConcurrencyStatus concurrencyStatus() {
        return queue.concurrencyStatus();
    }

    /** The decisions the root queue delegates to the gateway while launching and finishing work. */
    private final class QueueRuntime implements RootQueue.Runtime {

        @Override
        public boolean admissible(ExecutionSnapshot saved) {
            try {
                authority.verify(saved);
            } catch (SecurityException | NotFoundException invalidAuthority) {
                toolCalls.settleUndispatched(saved.executionId, "execution_authority_invalid");
                blockInference(saved.executionId, "execution_authority_invalid");
                return false;
            }
            return ensureInferenceBinding(saved.executionId);
        }

        @Override
        public boolean starting(ExecutionSnapshot admitted) {
            try {
                if (learningForeground(admitted)) {
                    foregroundStarted.accept(admitted.scope);
                }
            } catch (RuntimeException failure) {
                blockInference(admitted.executionId, "execution_start_failed");
                return false;
            }
            return true;
        }

        @Override
        public void run(String id, CancellationHandle cancellation) {
            runner.run(id, cancellation);
        }

        @Override
        public void finished(ExecutionSnapshot admitted) {
            try {
                if (learningForeground(admitted)) {
                    foregroundSettled.accept(admitted.scope);
                }
            } catch (RuntimeException failure) {
                log.warn("executionId={} stage=learning outcome=settlement_pending", admitted.executionId);
            }
        }

        @Override
        public void expired(String id) {
            toolCalls.settleAtTimeLimit(id);
        }

        /** Interactive root tasks are the only ones the learning loop treats as foreground work. */
        private boolean learningForeground(ExecutionSnapshot admitted) {
            return admitted.monitoring == null && !"scheduler".equals(admitted.scope.origin());
        }
    }

    public ExecutionSnapshot get(String id) {
        return repository.get(id);
    }

    public List<ExecutionSnapshot> list() {
        return repository.list();
    }

    public synchronized ExecutionSnapshot steer(String id, String text, TrustedScope actor) {
        return steer(id, text, actor, null, List.of());
    }

    public synchronized ExecutionSnapshot steer(
            String id,
            String text,
            TrustedScope actor,
            SourceTurn turn,
            List<SourceMessage> messages) {
        return steer(id, text, actor, turn, messages, List.of());
    }

    /**
     * A correction that also attaches files. They are stored on the execution before the correction is
     * saved and listed as metadata in the corrected turn; contents reach the model only through tool results.
     */
    public synchronized ExecutionSnapshot steer(
            String id,
            String text,
            TrustedScope actor,
            SourceTurn turn,
            List<SourceMessage> messages,
            List<UploadedFile> uploads) {
        requireText(text);
        var existing = repository.get(id);
        requireOwner(existing, actor);
        var context = sourceContext(existing, turn, messages);
        if (hasSourceTurn(existing, turn)) {
            return resumePending(existing);
        }
        queue.requireQueueCapacity(id);
        if (existing.status.terminal()) {
            throw new ConflictException("Terminal executions cannot be corrected");
        }
        var input = attachUploads(id, actor, text, turn, context, uploads);
        repository.update(id, -1, s -> {
            if (s.status.terminal()) {
                throw new ConflictException("Terminal executions cannot be corrected");
            }
            var correction = repository.inbox(id, input.text(), turn, input.context());
            s.goalGenerationInboxIds.add(correction.id());
            s.goalGeneration++;
            s.outputReports.clear();
            if (s.status == ExecutionStatus.waiting_for_user) {
                s.status = ExecutionStatus.running;
                s.deliveryTurn++;
            }
        });
        queue.launch(id);
        return get(id);
    }

    public synchronized ExecutionSnapshot answer(
            String id,
            List<String> answers,
            Long expectedRevision,
            TrustedScope actor) {
        return answer(id, answers, expectedRevision, actor, null, List.of());
    }

    public synchronized ExecutionSnapshot answer(
            String id,
            List<String> answers,
            Long expectedRevision,
            TrustedScope actor,
            SourceTurn turn,
            List<SourceMessage> messages) {
        return answer(id, answers, expectedRevision, actor, turn, messages, List.of());
    }

    /** An answer that also attaches files; see {@link #steer(String, String, TrustedScope, SourceTurn, List, List)}. */
    public synchronized ExecutionSnapshot answer(
            String id,
            List<String> answers,
            Long expectedRevision,
            TrustedScope actor,
            SourceTurn turn,
            List<SourceMessage> messages,
            List<UploadedFile> uploads) {
        List<String> actualAnswers = answers == null
                ? List.of()
                : answers.stream().filter(Objects::nonNull).filter(a -> !a.isBlank()).toList();
        if (actualAnswers.isEmpty()) {
            throw new IllegalArgumentException("Supply nonempty answers");
        }
        var existing = get(id);
        requireOwner(existing, actor);
        var context = sourceContext(existing, turn, messages);
        if (hasSourceTurn(existing, turn)) {
            return resumePending(existing);
        }
        queue.requireQueueCapacity(id);
        if (existing.status != ExecutionStatus.waiting_for_user) {
            throw new ConflictException("Execution is not waiting for input");
        }
        var input = attachUploads(id, actor, String.join("\n", actualAnswers), turn, context, uploads);
        repository.update(id, expectedRevision == null ? -1 : expectedRevision, s -> {
            if (s.status != ExecutionStatus.waiting_for_user) {
                throw new ConflictException("Execution is not waiting for input");
            }
            if (s.pendingQuestions.isEmpty() && s.calls.stream().anyMatch(AcceptedCall::awaitingApproval)) {
                throw new ConflictException(
                        "Execution is waiting for a tool approval, not an answer; approve or decline the call");
            }
            var correction = repository.inbox(id, input.text(), turn, input.context());
            s.goalGenerationInboxIds.add(correction.id());
            s.goalGeneration++;
            s.outputReports.clear();
            s.pendingQuestions.clear();
            s.status = ExecutionStatus.running;
            s.deliveryTurn++;
        });
        queue.launch(id);
        return get(id);
    }

    /** Pending sidecars count as accepted even if a process died before the next snapshot write. */
    public boolean hasSourceTurn(ExecutionSnapshot snapshot, SourceTurn turn) {
        return turn != null && (turn.equals(snapshot.sourceTurn)
                || snapshot.acceptedSourceTurns.contains(turn)
                || snapshot.sourceMessages.stream().anyMatch(message -> turn.equals(message.source()))
                || snapshot.pendingSourceMessages.stream().anyMatch(message -> turn.equals(message.source()))
                || repository.inbox(snapshot.executionId).stream().anyMatch(
                        c -> turn.equals(c.sourceTurn())
                                || c.sourceMessages().stream()
                                        .anyMatch(message -> turn.equals(message.source()))));
    }

    /** The text and source context of a later turn, after any attached files were stored. */
    private record TurnInput(String text, List<SourceMessage> context) {}

    /**
     * Stores the turn's files on the execution and lists them where the model will read this turn: in the
     * trusted source message of the turn when thread context is supplied, otherwise in the raw text.
     */
    private TurnInput attachUploads(
            String id,
            TrustedScope actor,
            String text,
            SourceTurn turn,
            List<SourceMessage> context,
            List<UploadedFile> uploads) {
        var files = uploads == null ? List.<UploadedFile>of() : uploads;
        if (files.isEmpty()) {
            return new TurnInput(text, context);
        }
        List<AttachmentStore.Stored> stored = attachments.attach(id, actor, files);
        if (stored.isEmpty()) {
            return new TurnInput(text, context);
        }
        String notice = uploadsNotice(stored);
        if (context.isEmpty()) {
            return new TurnInput(text + "\n\n" + notice, context);
        }
        int index = -1;
        for (int candidate = 0; candidate < context.size(); candidate++) {
            if (context.get(candidate).source().equals(turn)) {
                index = candidate;
            }
        }
        if (index < 0) {
            index = context.size() - 1;
        }
        var annotated = new ArrayList<>(context);
        var message = annotated.get(index);
        annotated.set(
                index,
                new SourceMessage(
                        message.source(),
                        message.authorId(),
                        message.botId(),
                        message.text() + "\n\n" + notice,
                        message.alreadyRepresented()));
        return new TurnInput(text, List.copyOf(annotated));
    }

    private static List<SourceMessage> sourceContext(
            ExecutionSnapshot snapshot,
            SourceTurn turn,
            List<SourceMessage> messages) {
        var context = messages == null ? List.<SourceMessage>of() : List.copyOf(messages);
        SourceMessage.validateThread(snapshot.scope, turn, context);
        if (turn != null && snapshot.sourceTurn != null
                && !snapshot.sourceTurn.threadId().equals(turn.threadId())) {
            throw new IllegalArgumentException("Source turn must belong to the execution thread");
        }
        return context;
    }

    private ExecutionSnapshot resumePending(ExecutionSnapshot snapshot) {
        if (snapshot.status == ExecutionStatus.waiting_for_user
                && repository.inbox(snapshot.executionId).stream()
                        .anyMatch(c -> !snapshot.consumedInboxIds.contains(c.id()))) {
            queue.requireQueueCapacity(snapshot.executionId);
            repository.update(snapshot.executionId, -1, s -> {
                if (s.status == ExecutionStatus.waiting_for_user) {
                    s.status = ExecutionStatus.running;
                    s.deliveryTurn++;
                }
            });
        }
        queue.launch(snapshot.executionId);
        return get(snapshot.executionId);
    }

    public synchronized ExecutionSnapshot cancel(String id, TrustedScope actor, boolean administrative) {
        return cancel(id, actor, administrative, null);
    }

    public synchronized ExecutionSnapshot cancel(
            String id,
            TrustedScope actor,
            boolean administrative,
            SourceTurn turn) {
        var snapshot = get(id);
        if (administrative) {
            requireAdministrator(actor);
        } else {
            requireOwner(snapshot, actor);
        }
        sourceContext(snapshot, turn, List.of());
        if (snapshot.status.terminal()) {
            return snapshot;
        }
        // Cancellation intent is durable before the worker is signalled.
        repository.update(id, -1, s -> {
            s.cancellationRequested = true;
            RootQueue.chargeQueueWait(s);
            if (turn != null) {
                s.acceptedSourceTurns.add(turn);
            }
            if (s.status == ExecutionStatus.waiting_for_user) {
                s.deliveryTurn++;
            }
        });
        if (!queue.interrupt(id)) {
            toolCalls.settleUndispatched(id, "cancelled");
            toolCalls.recoverSettlements(id);
            repository.update(id, -1, s -> {
                s.status = ExecutionStatus.cancelled;
                s.pendingQuestions.clear();
            });
        }
        return get(id);
    }

    public synchronized boolean settled(String id) {
        return queue.settled(id);
    }

    /** A returned handler does not imply a remote process has stopped. Delivery and deletion use this fence. */
    public void registerSettlementCheck(java.util.function.Predicate<String> check) {
        queue.registerSettlementCheck(check);
    }

    public synchronized void delete(String id, Instant expectedUpdatedAt) {
        var snapshot = get(id);
        if (!snapshot.status.terminal() || !settled(id)) {
            throw new ConflictException("Execution is not terminal and settled");
        }
        if (expectedUpdatedAt == null || !expectedUpdatedAt.equals(snapshot.updatedAt)) {
            throw new ConflictException("Stale execution revision");
        }
        deletionBarriers.forEach(barrier -> barrier.accept(id));
        deletionListeners.forEach(listener -> listener.accept(id));
        hooks.deleted(id);
        repository.delete(id, snapshot.revision);
        queue.forget(id);
    }

    public String artifact(String id, String artifact, long offset, int limit) {
        return repository.artifact(id, artifact, offset, limit);
    }

    public void registerDeletionListener(Consumer<String> listener) {
        deletionListeners.add(listener);
    }

    private boolean ensureInferenceBinding(String id) {
        var snapshot = repository.get(id);
        // Historical task types remain readable, but their retired runtime must never resume.
        if (snapshot.purpose != TaskPurpose.generic) {
            toolCalls.settleUndispatched(id, "inference_profile_unavailable");
            blockInference(id, "inference_profile_unavailable");
            return false;
        }
        String failure = incompatibleInference(snapshot.activeInferenceBinding());
        if (failure == null) {
            for (var child : snapshot.childRuns.values()) {
                if (child.status.terminal()) {
                    continue;
                }
                failure = incompatibleInference(child.inferenceBinding);
                if (failure != null) {
                    break;
                }
            }
        }
        if (failure != null) {
            blockInference(id, failure);
            return false;
        }
        return true;
    }

    private String incompatibleInference(InferenceBinding binding) {
        var resolution = PinnedInference.resolve(binding, profiles);
        return switch (resolution.status()) {
            case configured -> null;
            case unavailable -> "inference_profile_unavailable";
            case incompatible -> "inference_profile_incompatible";
        };
    }

    private void blockInference(String id, String reason) {
        repository.update(id, -1, s -> {
            if (s.status.terminal()) {
                return;
            }
            s.status = ExecutionStatus.blocked;
            RootQueue.chargeQueueWait(s);
            s.failure = reason;
            if (!s.blockers.contains(reason)) {
                s.blockers.add(reason);
            }
        });
    }

    /** Blocks a saved execution whose account or association can no longer be verified. */
    private void blockInvalidAuthority(String id) {
        repository.update(id, -1, state -> {
            RootQueue.chargeQueueWait(state);
            state.status = ExecutionStatus.blocked;
            state.failure = "execution_authority_invalid";
            if (!state.blockers.contains("execution_authority_invalid")) {
                state.blockers.add("execution_authority_invalid");
            }
        });
    }

    public void registerDeletionBarrier(Consumer<String> barrier) {
        deletionBarriers.add(Objects.requireNonNull(barrier));
    }

    /**
     * Startup recovery: every saved nonterminal task is re-verified, its interrupted calls are settled from
     * their receipts, and running work is relaunched in its persisted queue order. Nothing drains until the
     * whole saved set has been examined.
     */
    public synchronized void recover() {
        queue.beginRecovery();
        try {
            var saved = repository.list().stream().sorted(
                    Comparator
                            .comparingLong((ExecutionSnapshot s) -> s.queueSequence)
                            .thenComparing(s -> s.createdAt).thenComparing(s -> s.executionId))
                    .toList();
            queue.initializeSequence(saved.stream().mapToLong(s -> s.queueSequence).max().orElse(0));
            for (var original : saved) {
                if (!original.status.terminal()) {
                    recover(original);
                }
            }
        } finally {
            queue.endRecovery();
        }
        queue.drainQueue();
    }

    /** One saved nonterminal task; see {@link #recover()}. */
    private void recover(ExecutionSnapshot original) {
        String id = original.executionId;
        try {
            authority.verifyAccount(original.scope);
        } catch (SecurityException | NotFoundException invalidAuthority) {
            blockInvalidAuthority(id);
            return;
        }
        toolCalls.recoverSettlements(id);
        if (runner.settleMonitoringInput(id)) {
            if (get(id).queuedAt != null) {
                repository.update(id, -1, RootQueue::chargeQueueWait);
            }
            return;
        }
        var snapshot = get(id);
        if (snapshot.status.terminal()) {
            return;
        }
        try {
            if (!snapshot.cancellationRequested && "scheduler".equals(snapshot.scope.origin())) {
                authority.verifyAssociations(snapshot);
            }
        } catch (SecurityException | NotFoundException invalidAuthority) {
            // Existing effects keep their exact receipts even when automation can no longer resume.
            toolCalls.settleUndispatched(id, "execution_authority_invalid");
            blockInvalidAuthority(id);
            return;
        }
        if (!ensureInferenceBinding(id)) {
            return;
        }
        snapshot = get(id);
        if (snapshot.status == ExecutionStatus.waiting_for_user
                && repository.inbox(id).stream()
                        .anyMatch(c -> !original.consumedInboxIds.contains(c.id()))) {
            snapshot = repository.update(id, -1, s -> {
                s.status = ExecutionStatus.running;
                s.deliveryTurn++;
            });
        }
        if (snapshot.cancellationRequested) {
            toolCalls.settleUndispatched(id, "cancelled");
            repository.update(id, -1, s -> {
                RootQueue.chargeQueueWait(s);
                s.status = ExecutionStatus.cancelled;
            });
        } else if (snapshot.status == ExecutionStatus.running) {
            if (!snapshot.automaticResumption) {
                toolCalls.settleUndispatched(id, "automatic_resumption_disabled");
                repository.update(id, -1, s -> {
                    RootQueue.chargeQueueWait(s);
                    s.status = ExecutionStatus.blocked;
                    s.blockers.add("automatic_resumption_disabled");
                });
            } else {
                queue.launch(id);
            }
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (!queue.beginClose()) {
                return;
            }
        }
        queue.awaitWorkers();
        runner.close();
        toolCalls.close();
        hooks.close();
        log.info("stage=shutdown outcome=drained");
    }

    private void requireOwner(ExecutionSnapshot snapshot, TrustedScope actor) {
        authority.verifyAccount(actor);
        if (!snapshot.scope.sameOwner(actor)) {
            throw new SecurityException(
                    "Only the owning Velocity account may control this execution");
        }
    }

    private static void requireAdministrator(TrustedScope actor) {
        if (actor == null || !"local".equals(actor.origin())) {
            throw new SecurityException("Local operator action required");
        }
    }

    private static void requireText(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Correction text required");
        }
    }
}
