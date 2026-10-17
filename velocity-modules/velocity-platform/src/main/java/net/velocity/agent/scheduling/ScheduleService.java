package net.velocity.agent.scheduling;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import org.jobrunr.jobs.annotations.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.velocity.agent.AgentGateway;
import net.velocity.agent.SchedulerIngressGateway;
import net.velocity.agent.ToolRegistry;
import net.velocity.agent.persistence.ConflictException;
import net.velocity.agent.persistence.NotFoundException;
import net.velocity.agent.scheduling.ScheduleRecord.Action;
import net.velocity.agent.scheduling.ScheduleRecord.Occurrence;
import net.velocity.agent.scheduling.ScheduleRecord.Outcome;
import net.velocity.agent.scheduling.ScheduleRecord.Status;
import net.velocity.agent.state.ExecutionSnapshot;
import net.velocity.agent.state.ExecutionStatus;
import net.velocity.agent.state.SourceTurn;
import net.velocity.agent.state.TrustedScope;

/**
 * Single-process authority for definitions and occurrences. A per-schedule lock serializes
 * edits with dispatch; committed occurrences, rather than queue payloads, authorize work.
 */
public final class ScheduleService {

    public static final class UnavailableException extends IllegalStateException {

        public UnavailableException(String message) {
            super(message);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);
    private final ScheduleRepository repository;
    private final ScheduleJobs jobs;
    private final Supplier<SchedulerIngressGateway> ingress;
    private final Supplier<AgentGateway> gateway;
    private final ScheduleDelivery delivery;
    private final Clock clock;
    private final Duration maximumLateness;
    private final ReentrantLock[] locks = new ReentrantLock[256];
    private volatile MonitoringScheduleHandler monitoringHandler;
    private int monitoringOccurrenceRetention = 200;

    public ScheduleService(
            ScheduleRepository repository,
            ScheduleJobs jobs,
            Supplier<SchedulerIngressGateway> ingress,
            Supplier<AgentGateway> gateway,
            ScheduleDelivery delivery,
            Clock clock,
            Duration maximumLateness) {
        this.repository = Objects.requireNonNull(repository);
        this.jobs = Objects.requireNonNull(jobs);
        this.ingress = Objects.requireNonNull(ingress);
        this.gateway = Objects.requireNonNull(gateway);
        this.delivery = Objects.requireNonNull(delivery);
        this.clock = Objects.requireNonNull(clock);
        if (maximumLateness.isNegative()) {
            throw new IllegalArgumentException("Maximum lateness must be nonnegative");
        }
        this.maximumLateness = maximumLateness;
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    public void setMonitoringHandler(MonitoringScheduleHandler handler) {
        this.monitoringHandler = Objects.requireNonNull(handler);
    }

    public void setMonitoringOccurrenceRetention(int retained) {
        if (retained < 1) {
            throw new IllegalArgumentException("Monitoring occurrence retention must be positive");
        }
        this.monitoringOccurrenceRetention = retained;
    }

    public boolean available() {
        return jobs.available();
    }

    /** Monitoring mutations must acquire this lock before accessing their job or run records. */
    public <T> T withMonitoringScheduleLock(String scheduleId, Supplier<T> action) {
        return locked(scheduleId, action);
    }

    /** Idempotent trusted synchronization; no model-facing tool can set these association fields. */
    public Map<String, Object> upsertMonitoring(
            String scheduleId,
            String jobId,
            TrustedScope owner,
            String name,
            ScheduleTiming timing,
            Status status,
            long monitoringRevision) {
        ScheduleRecordCodec.name(scheduleId);
        ScheduleRecordCodec.name(jobId);
        if (monitoringRevision < 1
                || !List.of(Status.active, Status.paused, Status.cancelled).contains(status)) {
            throw new IllegalArgumentException("Invalid monitoring schedule revision or lifecycle");
        }
        Objects.requireNonNull(owner);
        owner.requireOwnerAccountId();
        Objects.requireNonNull(timing);
        if (name == null || name.isBlank() || name.length() > 200) {
            throw new IllegalArgumentException(
                    "Monitoring schedule name is required and bounded to 200 characters");
        }
        return locked(scheduleId, () -> {
            ScheduleRecord record;
            try {
                record = repository.get(scheduleId);
                if (!Objects.equals(record.monitoringJobId, jobId)
                        || !record.ownerAccountId.equals(owner.requireOwnerAccountId())) {
                    throw new ConflictException("Monitoring schedule association does not match its owner");
                }
                if (monitoringRevision < record.monitoringRevision) {
                    throw new ConflictException("Stale monitoring schedule revision");
                }
                if (record.monitoringRevision == monitoringRevision) {
                    if (!record.name.equals(name) || !record.timing.equals(timing)
                            || record.status != status) {
                        throw new ConflictException("Monitoring revision is bound to a different definition");
                    }
                    register(record);
                    return view(record, false);
                }
                if (record.status == Status.cancelled && status != Status.cancelled) {
                    throw new ConflictException("Closed monitoring schedules cannot be resumed");
                }
                Instant next = record.status == Status.active && status == Status.active
                        && record.timing.equals(timing)
                                ? record.nextOccurrence
                                : null;
                invalidatePending(record, "Monitoring definition changed");
                record.revision = Math.addExact(record.revision, 1);
                record.monitoringRevision = monitoringRevision;
                record.name = name;
                record.timing = timing;
                record.status = status;
                record.nextOccurrence = null;
                if (status == Status.active) {
                    plan(record, next == null ? timing.first(clock) : next);
                }
            } catch (NotFoundException absent) {
                record = new ScheduleRecord();
                record.id = scheduleId;
                record.monitoringJobId = jobId;
                record.monitoringRevision = monitoringRevision;
                record.owner = owner;
                record.ownerAccountId = owner.requireOwnerAccountId();
                record.name = name;
                record.action = Action.execution;
                record.prompt = "Run the associated persisted monitoring job";
                record.timing = timing;
                record.status = status;
                record.createdAt = clock.instant();
                record.updatedAt = record.createdAt;
                if (status == Status.active) {
                    plan(record, timing.first(clock));
                }
            }
            save(record);
            register(record);
            return view(record, false);
        });
    }

    /** One manual admission per monitoring revision, using the same lock and overlap gate as due work. */
    public String runMonitoringNow(String scheduleId, String jobId) {
        requireAvailable();
        return locked(scheduleId, () -> {
            var record = repository.get(scheduleId);
            if (!Objects.equals(jobId, record.monitoringJobId)) {
                throw new NotFoundException("Monitoring schedule not found");
            }
            if (record.status != Status.active) {
                throw new ConflictException("Monitoring job is not active");
            }
            String occurrenceId = UUID.nameUUIDFromBytes(
                    (record.id + ":" + record.monitoringRevision + ":manual")
                            .getBytes(StandardCharsets.UTF_8))
                    .toString();
            if (record.occurrences.containsKey(occurrenceId)) {
                return occurrenceId;
            }
            for (var prior : new ArrayList<>(record.occurrences.values())) {
                if (prior.outcome == Outcome.running) {
                    refresh(record, prior);
                }
            }
            if (record.occurrences.values().stream().anyMatch(o -> o.outcome == Outcome.running)) {
                save(record);
                throw new ConflictException("A monitoring run is already active");
            }
            var occurrence = new Occurrence();
            occurrence.id = occurrenceId;
            occurrence.ownerAccountId = record.ownerAccountId;
            occurrence.revision = record.revision;
            occurrence.monitoringRevision = record.monitoringRevision;
            occurrence.dueAt = clock.instant();
            occurrence.outcome = Outcome.running;
            occurrence.prompt = record.prompt;
            occurrence.executionId = SchedulerIngressGateway.executionId(record.id, occurrence.id);
            record.occurrences.put(occurrence.id, occurrence);
            save(record);
            refresh(record, occurrence);
            save(record);
            return occurrence.id;
        });
    }

    public Map<String, Object> create(
            ToolRegistry.Context context,
            Action action,
            Map<String, Object> input) {
        requireAvailable();
        context.cancellation().check();
        var record = new ScheduleRecord();
        record.id = UUID.randomUUID().toString();
        record.owner = context.scope();
        record.ownerAccountId = context.scope().requireOwnerAccountId();
        record.action = Objects.requireNonNull(action);
        record.name = requiredText(input, "name", 200);
        record.timing = timing(input.get("timing"));
        record.destination = destination(input.get("destination"));
        payload(record, input, true);
        validateAction(record);
        record.createdAt = clock.instant();
        record.updatedAt = record.createdAt;
        plan(record, record.timing.first(clock));
        return locked(record.id, () -> {
            context.cancellation().check();
            repository.save(record); // The saved intent survives failure to register with JobRunr.
            register(record);
            return view(record, false);
        });
    }

    public List<Map<String, Object>> list(TrustedScope owner) {
        String ownerAccountId = owner.requireOwnerAccountId();
        return repository.list().stream().filter(record -> record.ownerAccountId.equals(ownerAccountId))
                .sorted(Comparator.comparing((ScheduleRecord record) -> record.createdAt).reversed())
                .map(record -> view(record, false)).toList();
    }

    public Map<String, Object> get(TrustedScope owner, String id) {
        return locked(id, () -> view(owned(owner, id), true));
    }

    public Map<String, Object> update(TrustedScope owner, Map<String, Object> input) {
        String id = requiredText(input, "id", 128);
        return locked(id, () -> {
            var record = owned(owner, id);
            ordinaryMutation(record);
            revision(record, integer(input.get("expected_revision"), "expected_revision"));
            editable(record);
            if (!record.timing.recurring() && consumedOnce(record)) {
                throw new ConflictException(
                        "The one-time occurrence has already started; create a new schedule");
            }
            if (input.keySet().stream().noneMatch(key -> !List.of("id", "expected_revision").contains(key))) {
                throw new IllegalArgumentException("Specify a future schedule change");
            }
            if (input.containsKey("name")) {
                record.name = requiredText(input, "name", 200);
            }
            boolean retimed = input.containsKey("timing");
            if (retimed) {
                record.timing = timing(input.get("timing"));
            }
            payload(record, input, false);
            if (Boolean.TRUE.equals(input.get("clear_destination"))) {
                if (input.containsKey("destination")) {
                    throw new IllegalArgumentException("Cannot set and clear destination together");
                }
                record.destination = null;
            }
            if (input.containsKey("destination")) {
                record.destination = destination(input.get("destination"));
            }
            validateAction(record);
            Instant next = retimed ? record.timing.first(clock) : record.nextOccurrence;
            if (record.status == Status.active && next == null) {
                throw new ConflictException(
                        "The occurrence has already started; there is no future occurrence to update");
            }
            invalidatePending(record, "Schedule updated");
            record.revision = Math.addExact(record.revision, 1);
            if (record.status == Status.active) {
                plan(record, next);
            }
            save(record);
            register(record);
            return view(record, false);
        });
    }

    public Map<String, Object> transition(
            TrustedScope owner,
            String id,
            long expectedRevision,
            Status status) {
        if (!List.of(Status.active, Status.paused, Status.cancelled).contains(status)) {
            throw new IllegalArgumentException("Unsupported schedule transition");
        }
        return locked(id, () -> {
            var record = owned(owner, id);
            ordinaryMutation(record);
            revision(record, expectedRevision);
            editable(record);
            if (record.status == status) {
                return view(record, false);
            }
            Instant next = null;
            if (status == Status.active) {
                requireAvailable();
                if (!record.timing.recurring() && consumedOnce(record)) {
                    throw new ConflictException(
                            "A one-time occurrence that has started cannot be resumed again");
                }
                validateAction(record);
                next = record.timing.first(clock);
            }
            invalidatePending(
                    record,
                    status == Status.cancelled ? "Schedule cancelled" : "Schedule paused or resumed");
            record.status = status;
            record.revision = Math.addExact(record.revision, 1);
            record.nextOccurrence = null;
            if (status == Status.active) {
                plan(record, next);
            }
            save(record);
            register(record);
            return view(record, false);
        });
    }

    /** Called by JobRunr; arguments carry identities only, never prompts or authority. */
    @Job(name = "Dispatch saved Velocity schedule", retries = 0)
    public void dispatch(String scheduleId, String occurrenceId) {
        if (!jobs.available()) {
            return;
        }
        locked(scheduleId, () -> {
            var record = repository.get(scheduleId);
            var occurrence = record.occurrences.get(occurrenceId);
            if (occurrence == null || occurrence.outcome != Outcome.pending) {
                return null;
            }
            if (record.status != Status.active || occurrence.revision != record.revision) {
                finish(occurrence, Outcome.cancelled, "Schedule no longer authorizes this occurrence");
                save(record);
                return null;
            }
            if (occurrence.dueAt.isAfter(clock.instant())) {
                return null;
            }
            // Completion can occur between reconciliation polls. Only unfinished work blocks overlap.
            for (var prior : new ArrayList<>(record.occurrences.values())) {
                if (prior.outcome == Outcome.running) {
                    refresh(record, prior);
                }
            }
            boolean missed = record.timing.recurring()
                    && occurrence.dueAt.plus(maximumLateness).isBefore(clock.instant());
            boolean overlap = record.occurrences.values().stream()
                    .anyMatch(o -> o.outcome == Outcome.running);
            if (missed || overlap) {
                finish(
                        occurrence,
                        Outcome.skipped,
                        missed
                                ? "Missed recurring occurrence; backlog skipped"
                                : "Previous occurrence is still running or waiting for input");
            } else {
                occurrence.outcome = Outcome.running;
                occurrence.prompt = record.prompt;
                occurrence.message = record.message;
                occurrence.destination = record.destination;
                if (record.action == Action.execution) {
                    occurrence.executionId = SchedulerIngressGateway.executionId(record.id, occurrence.id);
                }
            }
            record.nextOccurrence = null;
            if (record.timing.recurring()) {
                plan(record, record.timing.nextAfter(clock.instant()));
            }
            save(record); // Claim, action snapshot, and the next due slot commit together.
            if (occurrence.outcome == Outcome.running) {
                refresh(record, occurrence);
            }
            completeIfFinished(record);
            save(record);
            register(record);
            return null;
        });
    }

    /** Repairs persistence/registration gaps and recovers admissions and delivery receipts. */
    @Job(name = "Reconcile Velocity schedules", retries = 0)
    public void reconcile() {
        var monitor = monitoringHandler;
        if (monitor != null) {
            try {
                monitor.reconcile();
            } catch (RuntimeException failure) {
                // Monitoring storage/repair failure must not stop ordinary schedules. Never log payloads.
                log.warn(
                        "Monitoring reconciliation needs attention ({})",
                        failure.getClass().getSimpleName());
            }
        }
        if (!jobs.available()) {
            return;
        }
        for (var saved : repository.list()) {
            try {
                locked(saved.id, () -> {
                    var record = repository.get(saved.id);
                    for (var occurrence : new ArrayList<>(record.occurrences.values())) {
                        if (occurrence.outcome == Outcome.running) {
                            refresh(record, occurrence);
                        }
                    }
                    if (record.status == Status.active && record.timing.recurring()
                            && record.nextOccurrence != null
                            && record.nextOccurrence.plus(maximumLateness).isBefore(clock.instant())) {
                        for (var occurrence : record.occurrences.values()) {
                            if (occurrence.outcome == Outcome.pending) {
                                finish(
                                        occurrence,
                                        Outcome.skipped,
                                        "Missed recurring occurrence; backlog skipped");
                            }
                        }
                        plan(record, record.timing.nextAfter(clock.instant()));
                    }
                    completeIfFinished(record);
                    save(record);
                    register(record);
                    return null;
                });
            } catch (RuntimeException failure) {
                // A broken occurrence cannot prevent other owners' reconciliation. Never log payloads.
                log.warn(
                        "Schedule reconciliation needs attention for {} ({})",
                        saved.id,
                        failure.getClass().getSimpleName());
            }
        }
    }

    private void refresh(ScheduleRecord record, Occurrence occurrence) {
        if (record.monitoringJobId != null) {
            var handler = monitoringHandler;
            if (handler != null) {
                handler.refresh(record, occurrence, authorized(record, occurrence));
            }
            // Missing integration is recoverable; never send a monitoring final answer through ordinary delivery.
            return;
        }
        if (record.action == Action.message) {
            deliver(
                    occurrence,
                    occurrence.message,
                    () -> authorized(record, occurrence)
                            ? delivery.deliver(
                                    occurrence.id,
                                    record.owner,
                                    occurrence.destination,
                                    occurrence.message)
                            : delivery.reconcile(
                                    occurrence.id,
                                    record.owner,
                                    occurrence.destination,
                                    occurrence.message));
            return;
        }
        ExecutionSnapshot execution;
        try {
            execution = gateway.get().get(occurrence.executionId);
        } catch (NotFoundException absent) {
            if (!authorized(record, occurrence)) {
                finish(
                        occurrence,
                        Outcome.cancelled,
                        "Schedule changed before agent admission; execution was not started");
                return;
            }
            try {
                if (occurrence.destination != null) {
                    delivery.verify(record.owner, occurrence.destination);
                }
                execution = ingress.get()
                        .execute(record.id, occurrence.id, occurrence.prompt, executionScope(record.owner));
            } catch (RuntimeException failure) {
                // Admission may have persisted before a hook/launch failed. Inspect its stable ID first.
                try {
                    execution = gateway.get().get(occurrence.executionId);
                } catch (NotFoundException stillAbsent) {
                    finish(occurrence, Outcome.failed, safeFailure(failure));
                    return;
                }
            }
        }
        verifyExecution(execution);
        occurrence.executionStatus = execution.status.name();
        if (!execution.status.terminal()) {
            return;
        } // Waiting for user input also blocks overlap.
        if (!gateway.get().settled(occurrence.executionId)) {
            return;
        }
        // Final accounting can be persisted after the first terminal snapshot.
        var settled = gateway.get().get(occurrence.executionId);
        verifyExecution(settled);
        occurrence.executionStatus = settled.status.name();
        if (!settled.status.terminal()) {
            return;
        }
        if (settled.status != ExecutionStatus.succeeded) {
            finish(occurrence, Outcome.failed, "Agent execution ended with status " + settled.status.name());
        } else if (occurrence.destination != null) {
            deliver(
                    occurrence,
                    settled.finalAnswer,
                    () -> delivery.deliverExecution(
                            occurrence.id,
                            record.owner,
                            occurrence.destination,
                            settled));
        } else {
            finish(occurrence, Outcome.succeeded, null);
        }
    }

    private void deliver(
            Occurrence occurrence,
            String text,
            Supplier<ScheduleDelivery.DeliveryResult> attempt) {
        try {
            delivery.validateText(occurrence.destination, text);
        } catch (IllegalArgumentException invalid) {
            finish(occurrence, Outcome.failed, "Delivery text is empty or exceeds the transport limit");
            return;
        }
        var result = attempt.get();
        occurrence.receipt = result.receipt();
        finish(occurrence, switch (result.state()) {
            case succeeded -> Outcome.succeeded;
            case failed -> Outcome.failed;
            case uncertain -> Outcome.uncertain;
        }, result.failure());
    }

    private void plan(ScheduleRecord record, Instant due) {
        record.nextOccurrence = due;
        if (due == null) {
            return;
        }
        var occurrence = new Occurrence();
        occurrence.id = UUID.nameUUIDFromBytes(
                (record.id + ":" + record.revision + ":" + due)
                        .getBytes(StandardCharsets.UTF_8))
                .toString();
        occurrence.revision = record.revision;
        occurrence.ownerAccountId = record.ownerAccountId;
        occurrence.monitoringRevision = record.monitoringRevision;
        occurrence.dueAt = due;
        record.occurrences.putIfAbsent(occurrence.id, occurrence);
    }

    private void register(ScheduleRecord record) {
        if (record.status != Status.active || record.nextOccurrence == null) {
            return;
        }
        try {
            requireAvailable();
            for (var occurrence : record.occurrences.values()) {
                if (occurrence.outcome == Outcome.pending && occurrence.revision == record.revision) {
                    jobs.ensure(record.id, occurrence.id, occurrence.dueAt);
                }
            }
            if (record.registrationFailure != null) {
                record.registrationFailure = null;
                save(record);
            }
        } catch (RuntimeException failure) {
            record.registrationFailure = "Job registration pending; reconciliation will retry";
            save(record);
        }
    }

    private void validateAction(ScheduleRecord record) {
        if (record.action == Action.execution && !gateway.get().available()) {
            throw new UnavailableException(
                    "Agent inference is unavailable; scheduled messages do not require inference");
        }
        if (record.action == Action.message && record.destination == null) {
            throw new IllegalArgumentException("A scheduled message requires an authorized destination");
        }
        if (record.destination != null) {
            delivery.verify(record.owner, record.destination);
            if (record.action == Action.message) {
                delivery.validateText(record.destination, record.message);
            }
        }
    }

    private void payload(ScheduleRecord record, Map<String, Object> input, boolean creating) {
        String field = record.action == Action.execution ? "prompt" : "message";
        String forbidden = record.action == Action.execution ? "message" : "prompt";
        if (input.containsKey(forbidden)) {
            throw new IllegalArgumentException("Payload does not match the schedule action");
        }
        if (creating || input.containsKey(field)) {
            String text = requiredText(input, field, record.action == Action.execution ? 100_000 : 40_000);
            if (record.action == Action.execution) {
                record.prompt = text;
            } else {
                record.message = text;
            }
        }
    }

    private void invalidatePending(ScheduleRecord record, String reason) {
        for (var occurrence : record.occurrences.values()) {
            if (occurrence.outcome == Outcome.pending) {
                finish(occurrence, Outcome.cancelled, reason);
            }
        }
        record.registrationFailure = null;
    }

    private void finish(Occurrence occurrence, Outcome outcome, String failure) {
        occurrence.outcome = outcome;
        occurrence.failure = failure;
        occurrence.completedAt = clock.instant();
    }

    private void completeIfFinished(ScheduleRecord record) {
        if (record.monitoringJobId != null) {
            return;
        } // A completed check never completes its continuing objective.
        if (record.status == Status.active && record.nextOccurrence == null
                && record.occurrences.values().stream()
                        .noneMatch(o -> o.outcome == Outcome.running || o.outcome == Outcome.pending)) {
            record.status = Status.completed;
        }
    }

    private static boolean consumedOnce(ScheduleRecord record) {
        Instant due = Instant.parse(record.timing.at());
        return record.occurrences.values().stream().filter(o -> due.equals(o.dueAt))
                .anyMatch(
                        o -> o.outcome == Outcome.running || o.outcome == Outcome.succeeded
                                || o.outcome == Outcome.failed || o.outcome == Outcome.uncertain);
    }

    private static boolean authorized(ScheduleRecord record, Occurrence occurrence) {
        return record.status == Status.active && record.revision == occurrence.revision;
    }

    private void save(ScheduleRecord record) {
        pruneMonitoringOccurrences(record);
        record.updatedAt = clock.instant().isBefore(record.updatedAt) ? record.updatedAt : clock.instant();
        repository.save(record);
    }

    private ScheduleRecord owned(TrustedScope owner, String id) {
        String ownerAccountId = owner.requireOwnerAccountId();
        var record = repository.get(id);
        if (!record.ownerAccountId.equals(ownerAccountId)) {
            throw new NotFoundException("Schedule not found");
        }
        return record;
    }

    private void requireAvailable() {
        if (!jobs.available()) {
            throw new UnavailableException(
                    "Scheduling is unavailable; set AGENT_SCHEDULING_ENABLED=true and verify JobRunr is available");
        }
    }

    private static void editable(ScheduleRecord record) {
        if (record.status == Status.cancelled || record.status == Status.completed) {
            throw new ConflictException(
                    "Cancelled or completed schedules cannot be changed; create a new schedule");
        }
    }

    private static void ordinaryMutation(ScheduleRecord record) {
        if (record.monitoringJobId != null) {
            throw new ConflictException("Use monitoring controls to change a monitoring-owned schedule");
        }
    }

    private void pruneMonitoringOccurrences(ScheduleRecord record) {
        if (record.monitoringJobId == null) {
            return;
        }
        // Keep every unresolved claim and uncertain external effect, plus a bounded completed history.
        var settled = record.occurrences.values().stream()
                .filter(
                        o -> o.outcome != Outcome.pending && o.outcome != Outcome.running
                                && o.outcome != Outcome.uncertain)
                .sorted(Comparator.comparing((Occurrence o) -> o.dueAt).reversed()).toList();
        for (int i = monitoringOccurrenceRetention; i < settled.size(); i++) {
            record.occurrences.remove(settled.get(i).id);
        }
    }

    private static void revision(ScheduleRecord record, long expected) {
        if (expected < 1 || record.revision != expected) {
            throw new ConflictException("Stale schedule revision; get the schedule before changing it");
        }
    }

    private static TrustedScope executionScope(TrustedScope owner) {
        return owner.withOrigin("scheduler");
    }

    /** Rechecks the saved authority during admission, recovery and scheduled tool dispatch. */
    public void verifyExecution(ExecutionSnapshot execution) {
        if (execution == null || execution.scope == null || execution.sourceTurn == null
                || !"scheduler".equals(execution.scope.origin())) {
            throw new SecurityException("Persisted schedule execution association required");
        }
        var source = execution.sourceTurn;
        var record = repository.get(source.threadId());
        var occurrence = record.occurrences.get(source.messageId());
        if (occurrence == null || record.action != Action.execution
                || !record.ownerAccountId.equals(execution.scope.requireOwnerAccountId())
                || !record.ownerAccountId.equals(occurrence.ownerAccountId)
                || !executionScope(record.owner).equals(execution.scope)
                || !new SourceTurn(
                        record.owner.workspaceId(),
                        record.owner.channelId(),
                        record.id,
                        occurrence.id).equals(source)
                || !SchedulerIngressGateway.executionId(record.id, occurrence.id)
                        .equals(execution.executionId)
                || !Objects.equals(occurrence.executionId, execution.executionId)
                || occurrence.prompt == null || occurrence.revision < 1
                || occurrence.revision > record.revision
                || occurrence.outcome == Outcome.pending || occurrence.outcome == Outcome.cancelled
                || (record.monitoringJobId == null
                        ? execution.monitoring != null
                                || !occurrence.prompt.equals(execution.originalRequest)
                        : execution.monitoring == null
                                || !record.monitoringJobId.equals(execution.monitoring.jobId())
                                || !record.id.equals(execution.monitoring.scheduleId())
                                || !occurrence.id.equals(execution.monitoring.occurrenceId())
                                || occurrence.monitoringRevision != execution.monitoring
                                        .definitionRevision())) {
            throw new SecurityException("Execution does not match its saved schedule occurrence and account");
        }
    }

    private ScheduleTiming timing(Object value) {
        var input = object(value, "timing");
        return ScheduleTiming.resolve(
                optionalText(input, "at"),
                input.containsKey("delay_seconds")
                        ? integer(input.get("delay_seconds"), "delay_seconds")
                        : null,
                optionalText(input, "cron"),
                optionalText(input, "timezone"),
                clock);
    }

    private static ScheduleDestination destination(Object value) {
        if (value == null) {
            return null;
        }
        var input = object(value, "destination");
        return new ScheduleDestination(
                requiredText(input, "transport", 40),
                requiredText(input, "channel_id", 128),
                optionalText(input, "thread_ts"));
    }

    private Map<String, Object> view(ScheduleRecord record, boolean detail) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", record.id);
        result.put("revision", record.revision);
        result.put("name", record.name);
        result.put("status", record.status.name());
        result.put("action_type", record.action.name());
        if (record.monitoringJobId != null) {
            result.put("monitoring_job_id", record.monitoringJobId);
            result.put("monitoring_revision", record.monitoringRevision);
        }
        var timing = new LinkedHashMap<String, Object>();
        timing.put("mode", record.timing.mode());
        timing.put("timezone", record.timing.timezone());
        if (record.timing.at() != null) {
            timing.put("at", record.timing.at());
        }
        if (record.timing.cron() != null) {
            timing.put("cron", record.timing.cron());
        }
        result.put("timing", timing);
        result.put("timezone", record.timing.timezone());
        result.put("next_occurrence", record.nextOccurrence == null ? "" : record.nextOccurrence.toString());
        if (record.nextOccurrence != null) {
            result.put(
                    "next_occurrence_local",
                    record.nextOccurrence.atZone(ZoneId.of(record.timing.timezone())).toString());
        }
        if (record.destination != null) {
            result.put("destination", destinationView(record.destination));
        }
        result.put("registration", record.registrationFailure == null ? "registered" : "pending");
        if (record.registrationFailure != null) {
            result.put("registration_failure", record.registrationFailure);
        }
        result.put("missed_run_policy", record.timing.recurring() ? "skip" : "run_once_after_recovery");
        result.put("overlap_policy", "skip");
        if (detail) {
            result.put(
                    record.action == Action.execution ? "prompt" : "message",
                    record.action == Action.execution ? record.prompt : record.message);
            result.put(
                    "occurrences",
                    record.occurrences.values().stream()
                            .sorted(Comparator.comparing((Occurrence o) -> o.dueAt).reversed()).limit(20)
                            .map(o -> {
                                var occurrence = new LinkedHashMap<String, Object>();
                                occurrence.put("id", o.id);
                                occurrence.put("revision", o.revision);
                                occurrence.put("due_at", o.dueAt.toString());
                                occurrence.put("outcome", o.outcome.name());
                                if (o.executionId != null) {
                                    occurrence.put("execution_id", o.executionId);
                                }
                                if (o.executionStatus != null) {
                                    occurrence.put("execution_status", o.executionStatus);
                                }
                                if (o.receipt != null) {
                                    occurrence.put("receipt", o.receipt);
                                }
                                if (o.failure != null) {
                                    occurrence.put("failure", o.failure);
                                }
                                if (o.completedAt != null) {
                                    occurrence.put("completed_at", o.completedAt.toString());
                                }
                                return occurrence;
                            }).toList());
        }
        return result;
    }

    private static Map<String, Object> destinationView(ScheduleDestination destination) {
        var view = new LinkedHashMap<String, Object>();
        view.put("transport", destination.transport());
        view.put("channel_id", destination.channelId());
        if (destination.threadId() != null) {
            view.put("thread_ts", destination.threadId());
        }
        return view;
    }

    private <T> T locked(String id, Supplier<T> action) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Schedule ID is required");
        }
        var lock = locks[(id.hashCode() & Integer.MAX_VALUE) % locks.length];
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    private static String safeFailure(RuntimeException failure) {
        if (failure instanceof net.velocity.agent.AdmissionCapacityException) {
            return "agent_queue_full: installation task queue is full; this occurrence did not start";
        }
        if (failure instanceof SecurityException) {
            return "Destination access was revoked or could not be verified";
        }
        if (failure instanceof ConflictException) {
            return "Execution identity was deleted or conflicts with durable state";
        }
        return "Agent admission failed; inspect runtime availability and saved execution state";
    }

    private static String requiredText(Map<String, Object> input, String key, int limit) {
        String text = optionalText(input, key);
        if (text == null || text.isBlank() || text.length() > limit) {
            throw new IllegalArgumentException(
                    key + " must be nonblank and at most " + limit + " characters");
        }
        return text;
    }

    private static String optionalText(Map<String, Object> input, String key) {
        if (!input.containsKey(key)) {
            return null;
        }
        if (!(input.get(key) instanceof String text)) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        return text;
    }

    private static long integer(Object value, String name) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        try {
            return new java.math.BigDecimal(number.toString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException invalid) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String name) {
        if (!(value instanceof Map<?, ?> map)
                || map.keySet().stream().anyMatch(key -> !(key instanceof String))) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        return (Map<String, Object>) map;
    }
}
