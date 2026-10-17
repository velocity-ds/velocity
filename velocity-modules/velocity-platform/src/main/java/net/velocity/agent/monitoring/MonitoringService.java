package net.velocity.agent.monitoring;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import net.velocity.ApplicationTime;
import net.velocity.agent.*;
import net.velocity.agent.persistence.*;
import net.velocity.agent.scheduling.*;
import net.velocity.agent.state.*;
import net.velocity.inference.SystemPrompts;

/** Single-installation lifecycle authority. All job writes share their schedule's dispatch lock. */
public final class MonitoringService implements MonitoringScheduleHandler {

    public static final class UnavailableException extends IllegalStateException {

        public UnavailableException(String message) {
            super(message);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(MonitoringService.class);
    private static final ObjectMapper JSON = new MonitoringRecordCodec().mapper;
    private final MonitoringRepository repository;
    private final ScheduleService schedules;
    private final Supplier<AgentGateway> gateway;
    private final Supplier<SchedulerIngressGateway> ingress;
    private final ScheduleDelivery delivery;
    private final MonitoringProperties limits;
    private final Clock clock;
    private final Object admissions = new Object();
    private boolean workerEnabled = true;

    public void setWorkerEnabled(boolean enabled) {
        workerEnabled = enabled;
    }

    public MonitoringService(
            MonitoringRepository repository,
            ScheduleService schedules,
            Supplier<AgentGateway> gateway,
            Supplier<SchedulerIngressGateway> ingress,
            ScheduleDelivery delivery,
            MonitoringProperties limits,
            Clock clock) {
        this.repository = repository;
        this.schedules = schedules;
        this.gateway = gateway;
        this.ingress = ingress;
        this.delivery = delivery;
        this.limits = limits;
        this.clock = clock;
    }

    /** Validates monitoring capability association without changing the execution account or context. */
    public void verifyExecution(ExecutionSnapshot execution) {
        var link = execution.monitoring;
        if (link == null) {
            throw new SecurityException("Monitoring association required");
        }
        var run = repository.getRun(link.occurrenceId());
        var job = repository.getJob(link.jobId());
        if (!job.ownerAccountId.equals(run.ownerAccountId)
                || !job.ownerAccountId.equals(execution.scope.requireOwnerAccountId())
                || !run.jobId.equals(job.id) || !run.scheduleId.equals(job.scheduleId)
                || !run.scheduleId.equals(link.scheduleId()) || !run.occurrenceId.equals(link.occurrenceId())
                || run.jobRevision != link.definitionRevision()
                || !run.executionId.equals(execution.executionId)
                || !Objects.equals(run.prompt, execution.originalRequest)
                || !SchedulerIngressGateway.executionId(run.scheduleId, run.occurrenceId)
                        .equals(execution.executionId)
                || !schedulerScope(job.owner).equals(execution.scope)
                || !new SourceTurn(
                        job.owner.workspaceId(),
                        job.owner.channelId(),
                        run.scheduleId,
                        run.occurrenceId).equals(execution.sourceTurn)
                || run.definition == null || !job.owner.equals(run.definition.owner)
                || job.status == MonitoringJob.Status.CLOSED
                || run.state == MonitoringRun.State.completed || run.state == MonitoringRun.State.cancelled) {
            throw new SecurityException("Monitoring owner association was not verified");
        }

    }

    public Map<String, Object> create(ToolRegistry.Context context, Map<String, Object> input) {
        actor(context.scope());
        JsonSchema.validate(MonitoringTools.creationSchema(), input);
        context.cancellation().check();
        var job = new MonitoringJob();
        job.id = UUID.randomUUID().toString();
        job.scheduleId = scheduleId(job.id);
        job.owner = context.scope();
        job.ownerAccountId = context.scope().requireOwnerAccountId();
        job.createdAt = clock.instant();
        job.updatedAt = job.createdAt;
        job.originalRequest = text(input, "original_request", "");
        job.name = text(input, "name", "");
        job.objective = text(input, "objective", job.originalRequest);
        job.timing = cadence(input.get("timing"));
        apply(job, input);
        if (job.destination == null && "slack".equals(job.owner.origin())) {
            String thread = null;
            try {
                var caller = gateway.get().get(context.executionId());
                if (!job.owner.equals(caller.scope)) {
                    throw new SecurityException("Monitoring caller scope mismatch");
                }
                if (caller.sourceTurn != null) {
                    thread = caller.sourceTurn.threadId();
                }
            } catch (NotFoundException absent) {
                /* Trusted adapter scope still identifies the current channel. */ }
            job.destination = new ScheduleDestination("slack", job.owner.channelId(), thread);
            delivery.verify(job.owner, job.destination);
        }
        job.status = MonitoringJob.Status.ACTIVE;
        job.nextDigestAt = job.digestTiming == null ? null : job.digestTiming.nextAfter(clock.instant());
        return schedules.withMonitoringScheduleLock(job.scheduleId, () -> {
            synchronized (admissions) {
                if (jobs().stream()
                        .filter(
                                j -> j.ownerAccountId.equals(job.ownerAccountId)
                                        && j.status != MonitoringJob.Status.CLOSED)
                        .count() >= limits.maximumActiveJobs()) {
                    throw new ConflictException("Maximum open monitoring jobs reached");
                }
                repository.saveJob(job);
            }
            synchronizeSchedule(job);
            return view(job);
        });
    }

    public Map<String, Object> list(TrustedScope owner, int offset, int limit) {
        actor(owner);
        page(offset, limit);
        var found = repository.listJobs(owner, offset, limit + 1);
        return pageView(
                "jobs",
                found.stream().limit(limit).map(this::view).toList(),
                offset,
                limit,
                found.size() > limit);
    }

    public Map<String, Object> get(TrustedScope owner, String id) {
        actor(owner);
        return view(owned(owner, id));
    }

    public Map<String, Object> update(TrustedScope owner, Map<String, Object> input) {
        actor(owner);
        JsonSchema.validate(MonitoringTools.updateSchema(), input);
        String id = (String) input.get("id");
        return schedules.withMonitoringScheduleLock(scheduleId(id), () -> {
            var job = owned(owner, id);
            revision(job, ((Number) input.get("expected_revision")).longValue());
            editable(job);
            if (input.size() <= 2) {
                throw new IllegalArgumentException("Supply a monitoring definition change");
            }
            boolean criteriaChanged = input.keySet().stream().anyMatch(
                    Set.of(
                            "objective",
                            "criteria",
                            "exclusions",
                            "sources",
                            "notification_instructions")::contains);
            if (input.containsKey("original_request")
                    && !job.originalRequest.equals(input.get("original_request"))) {
                throw new IllegalArgumentException(
                        "The original request is immutable; edit objective or criteria");
            }
            apply(job, input);
            if (criteriaChanged) {
                job.criteriaVersion++;
            }
            job.revision++;
            job.questions.clear();
            save(job);
            synchronizeSchedule(job);
            return view(job);
        });
    }

    public Map<String, Object> transition(
            TrustedScope owner,
            String id,
            long expectedRevision,
            MonitoringJob.Status target) {
        actor(owner);
        if (!Set.of(MonitoringJob.Status.ACTIVE, MonitoringJob.Status.PAUSED, MonitoringJob.Status.CLOSED)
                .contains(target)) {
            throw new IllegalArgumentException("Unsupported monitoring transition");
        }
        return schedules.withMonitoringScheduleLock(scheduleId(id), () -> {
            var job = owned(owner, id);
            revision(job, expectedRevision);
            editable(job);
            if (target == MonitoringJob.Status.ACTIVE && job.status == MonitoringJob.Status.NEEDS_INPUT
                    && !job.questions.isEmpty()) {
                throw new ConflictException(
                        "Update the monitoring definition with the requested information before resuming");
            }
            job.status = target;
            job.revision++;
            if (target == MonitoringJob.Status.CLOSED) {
                job.closedAt = clock.instant();
            }
            if (target == MonitoringJob.Status.ACTIVE) {
                job.questions.clear();
                checkEnd(job);
            }
            save(job);
            synchronizeSchedule(job);
            settleDeliveries(job);
            return view(job);
        });
    }

    public Map<String, Object> runNow(TrustedScope owner, String id, long expectedRevision) {
        actor(owner);
        return schedules.withMonitoringScheduleLock(scheduleId(id), () -> {
            var job = owned(owner, id);
            revision(job, expectedRevision);
            editable(job);
            checkEnd(job);
            if (job.status != MonitoringJob.Status.ACTIVE) {
                throw new ConflictException("Resume monitoring before running it");
            }
            if (job.activeRunId != null) {
                throw new ConflictException("A monitoring run is already active");
            }
            if (job.lastCheckAt != null
                    && clock.instant().isBefore(job.lastCheckAt.plus(limits.minimumCadence()))) {
                throw new ConflictException(
                        "Monitoring minimum cadence has not elapsed; wait until "
                                + job.lastCheckAt.plus(limits.minimumCadence()));
            }
            if (!schedules.available() || !gateway.get().available()) {
                throw new UnavailableException("Monitoring requires scheduling and configured inference");
            }
            job.revision++;
            save(job);
            synchronizeSchedule(job);
            schedules.runMonitoringNow(job.scheduleId, job.id);
            return view(repository.getJob(id));
        });
    }

    public Map<String, Object> history(TrustedScope owner, String id, String kind, int offset, int limit) {
        actor(owner);
        owned(owner, id);
        page(offset, limit);
        List<?> rows = switch (kind) {
            case "runs" -> repository.listRuns(id, offset, limit + 1).stream().map(this::runView).toList();
            case "observations" -> repository.listObservations(id, offset, limit + 1).stream()
                    .map(this::observationView).toList();
            case "updates" ->
                repository.listUpdates(id, offset, limit + 1).stream().map(this::updateView).toList();
            default -> throw new IllegalArgumentException("Unknown monitoring history");
        };
        return pageView("items", rows.stream().limit(limit).toList(), offset, limit, rows.size() > limit);
    }

    private void apply(MonitoringJob job, Map<String, Object> input) {
        job.name = text(input, "name", job.name);
        job.originalRequest = text(input, "original_request", job.originalRequest);
        job.objective = text(input, "objective", job.objective);
        job.criteria = text(input, "criteria", job.criteria);
        job.exclusions = text(input, "exclusions", job.exclusions);
        job.sources = text(input, "sources", job.sources);
        job.notificationInstructions = text(input, "notification_instructions", job.notificationInstructions);
        if (input.containsKey("timing")) {
            job.timing = cadence(input.get("timing"));
        }
        if (input.containsKey("notification_policy")) {
            job.notificationPolicy = MonitoringJob.NotificationPolicy
                    .valueOf((String) input.get("notification_policy"));
        }
        if (input.containsKey("initial_summary")) {
            job.initialSummary = (Boolean) input.get("initial_summary");
        }
        if (input.containsKey("digest_timing")) {
            job.digestTiming = cadence(input.get("digest_timing"));
            job.nextDigestAt = job.digestTiming.nextAfter(clock.instant());
        }
        if (job.notificationPolicy == MonitoringJob.NotificationPolicy.digest && job.digestTiming == null) {
            job.digestTiming = cadence(null);
        }
        if (input.containsKey("end_at")) {
            String end = (String) input.get("end_at");
            job.endAt = end.isBlank() ? null : Instant.parse(end);
            if (job.endAt != null && !job.endAt.isAfter(clock.instant())) {
                throw new IllegalArgumentException("end_at must be a future absolute instant");
            }
        }
        job.completionCondition = text(input, "completion_condition", job.completionCondition);
        if (Boolean.TRUE.equals(input.get("clear_completion_rule"))) {
            job.completionRule.clear();
        }
        if (input.containsKey("completion_rule")) {
            if (Boolean.TRUE.equals(input.get("clear_completion_rule"))) {
                throw new IllegalArgumentException("Cannot set and clear completion rule together");
            }
            job.completionRule = new LinkedHashMap<>();
            object(input.get("completion_rule")).forEach((k, v) -> job.completionRule.put(k, (String) v));
        }
        if (Boolean.TRUE.equals(input.get("clear_destination"))) {
            job.destination = null;
        }
        if (input.containsKey("destination")) {
            if (Boolean.TRUE.equals(input.get("clear_destination"))) {
                throw new IllegalArgumentException("Cannot set and clear destination together");
            }
            var value = object(input.get("destination"));
            job.destination = new ScheduleDestination(
                    (String) value.get("transport"),
                    (String) value.get("channel_id"),
                    (String) value.get("thread_ts"));
            delivery.verify(job.owner, job.destination);
        }
        if (job.name.isBlank() || job.originalRequest.isBlank() || job.objective.isBlank()) {
            throw new IllegalArgumentException("Name, original request and objective must be nonblank");
        }
        int definitionChars = java.util.stream.Stream
                .of(
                        job.name,
                        job.originalRequest,
                        job.objective,
                        job.criteria,
                        job.exclusions,
                        job.sources,
                        job.notificationInstructions,
                        job.completionCondition)
                .filter(Objects::nonNull).mapToInt(String::length).sum();
        if (definitionChars > 48000) {
            throw new IllegalArgumentException("Combined monitoring instructions exceed 48000 characters");
        }
        String fact = job.completionRule.get("fact");
        if (fact != null && (fact.contains(".") || fact.startsWith("$"))) {
            throw new IllegalArgumentException(
                    "Completion fact keys cannot contain dots or start with dollar signs");
        }
    }

    private ScheduleTiming cadence(Object input) {
        var values = input == null
                ? Map.<String, Object>of("cron", "0 0 9 * * *", "timezone", ApplicationTime.zone().getId())
                : object(input);
        var timing = ScheduleTiming
                .resolve(null, null, (String) values.get("cron"), (String) values.get("timezone"), clock);
        Instant prior = clock.instant();
        // Validate representative future gaps; runtime admission also enforces the minimum on every run.
        for (int i = 0; i < 128; i++) {
            Instant next = timing.nextAfter(prior);
            if (next == null) {
                throw new IllegalArgumentException("Cadence has no future occurrence");
            }
            if (i > 0 && next.isBefore(prior.plus(limits.minimumCadence()))) {
                throw new IllegalArgumentException("Monitoring cadence is below the installation minimum");
            }
            prior = next;
        }
        return timing;
    }

    private void synchronizeSchedule(MonitoringJob job) {
        try {
            var status = switch (job.status) {
                case ACTIVE -> ScheduleRecord.Status.active;
                case CLOSED -> ScheduleRecord.Status.cancelled;
                default -> ScheduleRecord.Status.paused;
            };
            schedules.upsertMonitoring(
                    job.scheduleId,
                    job.id,
                    job.owner,
                    job.name,
                    job.timing,
                    status,
                    job.revision);
            var schedule = schedules.get(job.owner, job.scheduleId);
            String due = (String) schedule.get("next_occurrence");
            job.nextDue = due == null || due.isBlank() ? null : Instant.parse(due);
            job.registrationFailure = "pending".equals(schedule.get("registration"))
                    ? "Schedule queue registration is pending reconciliation"
                    : null;
            save(job);
        } catch (RuntimeException failure) {
            job.registrationFailure = "Schedule registration is pending reconciliation";
            save(job); // Saved job is the repair intent; creation must not claim registration succeeded.
        }
    }

    @Override
    public void reconcile() {
        for (var saved : jobs()) {
            try {
                schedules.withMonitoringScheduleLock(saved.scheduleId, () -> {
                    var job = repository.getJob(saved.id);
                    checkEnd(job);
                    synchronizeSchedule(job);
                    prepareDigest(job);
                    settleDeliveries(job);
                    repository
                            .prune(job.id, clock.instant().minus(limits.retention()), limits.retainedRuns());
                    return null;
                });
            } catch (RuntimeException failure) {
                log.warn(
                        "Monitoring reconciliation needs attention for {} ({})",
                        saved.id,
                        failure.getClass().getSimpleName());
            }
        }
    }

    @Override
    public void refresh(
            ScheduleRecord schedule,
            ScheduleRecord.Occurrence occurrence,
            boolean allowAdmission) {
        var job = repository.getJob(schedule.monitoringJobId);
        if (!job.scheduleId.equals(schedule.id) || !job.owner.equals(schedule.owner)
                || !job.ownerAccountId.equals(schedule.ownerAccountId)
                || !job.ownerAccountId.equals(occurrence.ownerAccountId)) {
            throw new SecurityException("Monitoring schedule association mismatch");
        }
        checkEnd(job);
        job.nextDue = schedule.nextOccurrence;
        MonitoringRun run;
        try {
            run = repository.getRun(occurrence.id);
        } catch (NotFoundException absent) {
            if (!allowAdmission || job.status != MonitoringJob.Status.ACTIVE
                    || job.revision != occurrence.monitoringRevision) {
                finishOccurrence(
                        occurrence,
                        ScheduleRecord.Outcome.cancelled,
                        "Monitoring definition no longer authorizes admission");
                return;
            }
            synchronized (admissions) {
                if (job.activeRunId != null || repository.activeRunCount() >= limits.concurrentRuns()) {
                    finishOccurrence(
                            occurrence,
                            ScheduleRecord.Outcome.skipped,
                            "Monitoring concurrency limit reached");
                    return;
                }
                if (job.lastCheckAt != null
                        && clock.instant().isBefore(job.lastCheckAt.plus(limits.minimumCadence()))) {
                    finishOccurrence(
                            occurrence,
                            ScheduleRecord.Outcome.skipped,
                            "Monitoring minimum cadence has not elapsed");
                    return;
                }
                run = new MonitoringRun();
                run.id = occurrence.id;
                run.ownerAccountId = job.ownerAccountId;
                run.jobId = job.id;
                run.scheduleId = schedule.id;
                run.occurrenceId = occurrence.id;
                run.executionId = occurrence.executionId;
                run.jobRevision = job.revision;
                run.criteriaVersion = job.criteriaVersion;
                run.definition = new MonitoringRecordCodec().copy(job);
                run.baseline = !job.baselineEstablished;
                run.createdAt = clock.instant();
                run.prompt = context(job);
                repository.saveRun(run); // Prepared context fixes admission and replay identities across recovery.
                job.activeRunId = run.id;
                job.lastCheckAt = run.createdAt;
                save(job);
            }
        }
        if (!job.ownerAccountId.equals(run.ownerAccountId)
                || !run.jobId.equals(job.id) || !run.scheduleId.equals(schedule.id)
                || !run.executionId.equals(occurrence.executionId)
                || !run.occurrenceId.equals(occurrence.id)
                || run.jobRevision != occurrence.monitoringRevision) {
            throw new SecurityException("Monitoring run association mismatch");
        }
        if (run.state == MonitoringRun.State.completed || run.state == MonitoringRun.State.cancelled) {
            finishOccurrence(
                    occurrence,
                    run.state == MonitoringRun.State.cancelled
                            ? ScheduleRecord.Outcome.cancelled
                            : run.outcome == MonitoringResult.Outcome.failed
                                    ? ScheduleRecord.Outcome.failed
                                    : ScheduleRecord.Outcome.succeeded,
                    run.failure);
            return;
        }
        if (run.state == MonitoringRun.State.finalizing) {
            finalizeRun(job, run);
            finishOccurrence(occurrence, ScheduleRecord.Outcome.succeeded, run.failure);
            return;
        }
        if (job.activeRunId == null) {
            job.activeRunId = run.id;
            job.lastCheckAt = run.createdAt;
            save(job);
        }
        ExecutionSnapshot execution;
        try {
            execution = gateway.get().get(run.executionId);
        } catch (NotFoundException absent) {
            if (!allowAdmission || job.status != MonitoringJob.Status.ACTIVE
                    || job.revision != run.jobRevision) {
                run.state = MonitoringRun.State.cancelled;
                run.completedAt = clock.instant();
                repository.saveRun(run);
                if (run.id.equals(job.activeRunId)) {
                    job.activeRunId = null;
                    save(job);
                }
                finishOccurrence(
                        occurrence,
                        ScheduleRecord.Outcome.cancelled,
                        "Definition changed before admission");
                return;
            }
            try {
                execution = ingress.get().executeMonitoring(
                        schedule.id,
                        occurrence.id,
                        job.id,
                        run.jobRevision,
                        run.prompt,
                        SystemPrompts.MONITORING_RUN_INSTRUCTIONS,
                        schedulerScope(job.owner),
                        limits.executionLimits());
            } catch (RuntimeException unavailable) {
                try {
                    execution = gateway.get().get(run.executionId);
                } catch (NotFoundException stillAbsent) {
                    run.result = failed(
                            unavailable instanceof net.velocity.agent.AdmissionCapacityException
                                    ? "agent_queue_full: installation task queue is full; run again when capacity is available."
                                    : "Run could not start. Configure inference and scheduling, then resume or run again.");
                    run.state = MonitoringRun.State.finalizing;
                    repository.saveRun(run);
                    finalizeRun(job, run);
                    finishOccurrence(occurrence, ScheduleRecord.Outcome.failed, run.summary);
                    return;
                }
            }
        }
        if (!new MonitoringExecution(job.id, schedule.id, occurrence.id, run.jobRevision)
                .equals(execution.monitoring)
                || !Objects.equals(run.prompt, execution.originalRequest)
                || !schedulerScope(job.owner).equals(execution.scope)
                || !new SourceTurn(job.owner.workspaceId(), job.owner.channelId(), schedule.id, occurrence.id)
                        .equals(execution.sourceTurn)) {
            throw new SecurityException("Execution does not match its persisted monitoring association");
        }
        occurrence.executionStatus = execution.status.name();
        if (execution.status == ExecutionStatus.waiting_for_user
                || execution.status == ExecutionStatus.blocked && !execution.pendingQuestions.isEmpty()) {
            if (run.result == null) {
                var questions = execution.pendingQuestions.stream().map(Question::text).limit(10).toList();
                if (questions.isEmpty()) {
                    questions = List.of(
                            "Update the monitoring definition with the missing information, then resume.");
                }
                run.result = new MonitoringResult(
                        MonitoringResult.Outcome.needs_input,
                        "Monitoring needs owner input",
                        List.of(),
                        List.of(),
                        List.of(),
                        "Unverified",
                        Map.of(),
                        questions,
                        List.of(),
                        List.of());
                repository.saveRun(run); // Preserve question before cancellation clears runtime questions.
            }
            if (!execution.status.terminal()) {
                gateway.get().cancel(run.executionId, schedulerScope(job.owner), false);
            }
            execution = gateway.get().get(run.executionId);
        } else if (!execution.status.terminal()
                && !clock.instant().isBefore(run.createdAt.plus(limits.runDuration()))) {
            if (run.result == null) {
                run.result = failed("Monitoring run exceeded its wall-clock deadline");
                repository.saveRun(run);
            }
            gateway.get().cancel(run.executionId, schedulerScope(job.owner), false);
            execution = gateway.get().get(run.executionId);
        }
        if (!execution.status.terminal() || !gateway.get().settled(run.executionId)) {
            return;
        }
        if (run.result == null) {
            run.result = reportedResult(execution);
        }
        run.state = MonitoringRun.State.finalizing;
        repository.saveRun(run);
        finalizeRun(job, run);
        finishOccurrence(
                occurrence,
                run.outcome == MonitoringResult.Outcome.failed
                        ? ScheduleRecord.Outcome.failed
                        : ScheduleRecord.Outcome.succeeded,
                run.failure);
    }

    private void finalizeRun(MonitoringJob job, MonitoringRun run) {
        if (!run.planPrepared) {
            try {
                preparePlan(job, run);
            } catch (IllegalArgumentException | NotFoundException invalid) {
                log.warn("Monitoring run {} result was rejected: {}", run.id, invalid.getMessage());
                run.result = failed(rejected(invalid));
                run.observationPlan.clear();
                run.updatePlan.clear();
                run.observationIds.clear();
                run.updateIds.clear();
            }
            run.planPrepared = true;
            repository.saveRun(run); // All novelty decisions precede observation writes.
        }
        for (var observation : run.observationPlan) {
            repository.saveObservation(observation);
        }
        for (var update : run.updatePlan) {
            try {
                repository.getUpdate(update.id);
            } catch (NotFoundException absent) {
                repository.saveUpdate(update);
            }
        }
        if (!run.id.equals(job.lastAppliedRunId)) {
            job.lastAppliedRunId = run.id;
            if (run.id.equals(job.activeRunId)) {
                job.activeRunId = null;
            }
            job.health = run.result.outcome().name();
            if (run.criteriaVersion == job.criteriaVersion) {
                job.workingSummary = run.result.summary();
                job.limitations = new ArrayList<>(run.result.limitations());
                if (run.result.outcome() == MonitoringResult.Outcome.success
                        || run.result.outcome() == MonitoringResult.Outcome.partial) {
                    job.baselineEstablished = true;
                }
                if (run.result.outcome() == MonitoringResult.Outcome.success) {
                    job.lastSuccessAt = clock.instant();
                    job.baselineEstablished = true;
                }
                if (run.result.outcome() == MonitoringResult.Outcome.success
                        || run.result.outcome() == MonitoringResult.Outcome.partial) {
                    job.checkpoints.putAll(run.result.checkpoints());
                    while (job.checkpoints.size() > 50 || encode(job.checkpoints).length() > 12000) {
                        job.checkpoints.remove(job.checkpoints.keySet().iterator().next());
                    }
                }
                if (run.result.outcome() == MonitoringResult.Outcome.needs_input) {
                    job.questions = new ArrayList<>(run.result.questions());
                    if (job.status == MonitoringJob.Status.ACTIVE) {
                        job.status = MonitoringJob.Status.NEEDS_INPUT;
                        job.revision++;
                    }
                }
                if (completionSatisfied(job, run) && job.status == MonitoringJob.Status.ACTIVE) {
                    job.status = MonitoringJob.Status.CLOSED;
                    job.closedAt = clock.instant();
                    job.revision++;
                }
            }
            save(job);
        }
        run.outcome = run.result.outcome();
        run.summary = run.result.summary();
        run.completedAt = clock.instant();
        run.state = MonitoringRun.State.completed;
        repository.saveRun(run);
        prepareDigest(job);
        settleDeliveries(job);
    }

    private void preparePlan(MonitoringJob job, MonitoringRun run) {
        if (run.result.outcome() == MonitoringResult.Outcome.needs_input
                && job.status != MonitoringJob.Status.CLOSED && run.criteriaVersion == job.criteriaVersion) {
            var question = new MonitoringUpdate();
            question.id = identity(run.id + ":question");
            question.ownerAccountId = job.ownerAccountId;
            question.jobId = job.id;
            question.runId = run.id;
            question.destination = run.definition.destination;
            question.createdAt = clock.instant();
            question.content = bounded(
                    "Monitoring needs input — " + job.name + "\nJob: " + job.id + "\n\n"
                            + String.join("\n", run.result.questions())
                            + "\n\nUpdate this monitoring definition with the missing information, then resume.",
                    limits.notificationChars());
            run.updatePlan.add(question);
            run.updateIds.add(question.id);
        }
        var references = new LinkedHashMap<String, MonitoringObservation>();
        var changes = new HashMap<String, String>();
        var limitations = new ArrayList<>(run.result.limitations());
        for (var finding : run.result.observations()) {
            var prior = repository.latestObservation(job.id, finding.sourceId(), finding.itemId());
            if (prior != null && !job.ownerAccountId.equals(prior.ownerAccountId)) {
                throw new SecurityException("Observation account association mismatch");
            }
            String ignoredClaim = ignoredPriorClaim(job, finding);
            if (ignoredClaim != null) {
                log.info("Monitoring run {}: {}", run.id, ignoredClaim);
                if (limitations.size() < 30) {
                    limitations.add(ignoredClaim);
                }
            }
            String fingerprint = ObservationFingerprint.fingerprint(finding.facts());
            boolean matched = run.result.updates().stream()
                    .anyMatch(update -> update.observationKeys().contains(finding.key()));
            boolean newlyRelevant = prior != null && !prior.matched && matched;
            boolean changed = prior == null || !fingerprint.equals(prior.fingerprint);
            MonitoringObservation observed = prior;
            if (changed || prior.matched != matched || prior.criteriaVersion != run.criteriaVersion) {
                observed = new MonitoringObservation();
                observed.id = ObservationFingerprint
                        .versionId(job.id, run.id, finding.sourceId(), finding.itemId(), fingerprint);
                observed.ownerAccountId = job.ownerAccountId;
                observed.jobId = job.id;
                observed.runId = run.id;
                observed.sourceId = finding.sourceId();
                observed.itemId = finding.itemId();
                observed.fingerprint = fingerprint;
                observed.observedAt = run.createdAt;
                observed.sourceTime = finding.sourceTime();
                observed.facts = new LinkedHashMap<>(finding.facts());
                observed.evidence = new ArrayList<>(finding.evidence());
                observed.matched = matched;
                observed.priorObservationId = prior == null ? null : prior.id;
                observed.relevance = finding.relevance();
                observed.criteriaVersion = run.criteriaVersion;
                run.observationPlan.add(observed);
            }
            references.put(finding.key(), observed);
            run.observationIds.add(observed.id);
            changes.put(
                    finding.key(),
                    prior == null
                            ? "new"
                            : newlyRelevant ? "newly_relevant" : changed ? "changed" : "unchanged");
        }
        if (limitations.size() > run.result.limitations().size()) {
            run.result = withLimitations(run.result, limitations);
        }
        if (job.status == MonitoringJob.Status.CLOSED || run.criteriaVersion != job.criteriaVersion
                || run.result.outcome() == MonitoringResult.Outcome.failed
                || run.result.outcome() == MonitoringResult.Outcome.needs_input) {
            return;
        }
        if (run.baseline && !run.definition.initialSummary) {
            return;
        }
        var selected = new LinkedHashSet<String>();
        var paragraphs = new ArrayList<String>();
        for (var candidate : run.result.updates()) {
            boolean eligible = candidate.observationKeys().stream().anyMatch(key -> {
                String change = changes.get(key);
                return switch (candidate.change()) {
                    case new_match -> "new".equals(change) || "newly_relevant".equals(change);
                    case material_change, condition -> !"unchanged".equals(change);
                };
            });
            if (!eligible) {
                continue;
            }
            // Never repackage an unchanged observation merely because a sibling changed.
            for (String key : candidate.observationKeys()) {
                if (!"unchanged".equals(changes.get(key))) {
                    selected.add(references.get(key).id);
                }
            }
            paragraphs.add(
                    (candidate.text() == null || candidate.text().isBlank()
                            ? candidate.relevance()
                            : candidate.text()) + "\nRelevance: " + candidate.relevance());
        }
        if (paragraphs.isEmpty()) {
            return;
        }
        var update = new MonitoringUpdate();
        update.id = identity(run.id + ":update");
        update.ownerAccountId = job.ownerAccountId;
        update.jobId = job.id;
        update.runId = run.id;
        String heading = run.baseline
                ? "Initial baseline (existing findings; publication times are separate)"
                : "Monitoring update";
        update.content = bounded(
                heading + " — " + run.definition.name + "\n\n" + String.join("\n\n", paragraphs)
                        + "\n\nCoverage: " + run.result.outcome() + ". " + run.result.freshness(),
                run.definition.notificationPolicy == MonitoringJob.NotificationPolicy.digest
                        ? limits.notificationChars() - 300
                        : limits.notificationChars());
        update.observationIds = new ArrayList<>(selected);
        update.destination = run.definition.destination;
        update.createdAt = clock.instant();
        update.baseline = run.baseline;
        update.state = run.definition.notificationPolicy == MonitoringJob.NotificationPolicy.digest
                ? MonitoringUpdate.State.queued
                : MonitoringUpdate.State.pending;
        run.updatePlan.add(update);
        run.updateIds.add(update.id);
    }

    private boolean completionSatisfied(MonitoringJob job, MonitoringRun run) {
        if (job.completionRule.isEmpty() || run.result.outcome() != MonitoringResult.Outcome.success
                || run.jobRevision != job.revision) {
            return false;
        }
        var rule = job.completionRule;
        return run.result.observations().stream().anyMatch(
                o -> o.sourceId().equals(rule.get("source_id"))
                        && o.itemId().equals(rule.get("item_id")) && !o.evidence().isEmpty()
                        && o.facts().containsKey(rule.get("fact"))
                        && o.facts().get(rule.get("fact")).isValueNode()
                        && o.facts().get(rule.get("fact")).asText().equals(rule.get("equals"))
                        && run.result.completionEvidence().contains(o.key()));
    }

    private void prepareDigest(MonitoringJob job) {
        if (job.status != MonitoringJob.Status.ACTIVE
                || job.notificationPolicy != MonitoringJob.NotificationPolicy.digest) {
            return;
        }
        Instant due = job.nextDigestAt;
        if (due == null) {
            due = job.digestTiming.nextAfter(job.createdAt);
        }
        if (due.isAfter(clock.instant())) {
            return;
        }
        String batchId = identity(job.id + ":digest:" + due);
        MonitoringUpdate batch;
        try {
            batch = repository.getUpdate(batchId);
        } catch (NotFoundException absent) {
            batch = new MonitoringUpdate();
            batch.id = batchId;
            batch.ownerAccountId = job.ownerAccountId;
            batch.jobId = job.id;
            batch.runId = "digest";
            batch.createdAt = clock.instant();
            batch.destination = job.destination;
            String heading = "Monitoring digest — " + job.name + "\n\n";
            var chosen = new ArrayList<MonitoringUpdate>();
            int size = heading.length();
            boolean anyQueued = false;
            for (var candidate : unresolved(job.id)) {
                verifyUpdate(job, candidate);
                if (candidate.state != MonitoringUpdate.State.queued || candidate.bundledInto != null) {
                    continue;
                }
                anyQueued = true;
                if (chosen.size() >= limits.results()) {
                    break;
                }
                if (size + candidate.content.length() + 2 > limits.notificationChars()) {
                    continue;
                }
                chosen.add(candidate);
                size += candidate.content.length() + 2;
            }
            if (anyQueued && chosen.isEmpty()) {
                // A pre-existing queue entry may exceed a newly reduced installation limit.
                // Keep it intact and visible rather than claim it was included in a truncated digest.
                job.health = "notification_limit";
                save(job);
                return;
            }
            batch.sourceUpdateIds = chosen.stream().map(u -> u.id).toList();
            batch.observationIds = chosen.stream().flatMap(u -> u.observationIds.stream()).distinct()
                    .toList();
            batch.content = heading + (chosen.isEmpty()
                    ? "No newly selected findings since the previous digest. Last check: " + job.lastCheckAt
                            + "; health: " + job.health
                    : String.join("\n\n", chosen.stream().map(u -> u.content).toList()));
            repository.saveUpdate(batch); // Batch intent reserves the exact versions before their queue entries move.
        }
        for (String id : batch.sourceUpdateIds) {
            var source = repository.getUpdate(id);
            verifyUpdate(job, source);
            if (source.bundledInto == null) {
                source.bundledInto = batch.id;
                source.state = MonitoringUpdate.State.bundled;
                repository.saveUpdate(source);
            }
        }
        job.lastDigestAt = due;
        job.nextDigestAt = job.digestTiming.nextAfter(clock.instant());
        save(job);
    }

    private void settleDeliveries(MonitoringJob job) {
        for (var update : unresolved(job.id)) {
            verifyUpdate(job, update);
            boolean allow = job.status != MonitoringJob.Status.CLOSED;
            if ((update.state == MonitoringUpdate.State.pending
                    || update.state == MonitoringUpdate.State.queued) && !allow) {
                update.state = MonitoringUpdate.State.cancelled;
                update.completedAt = clock.instant();
                repository.saveUpdate(update);
                continue;
            }
            if (update.state == MonitoringUpdate.State.queued) {
                if (job.notificationPolicy == MonitoringJob.NotificationPolicy.digest) {
                    continue;
                }
                update.state = MonitoringUpdate.State.pending;
                repository.saveUpdate(update);
            }
            if (update.destination == null) {
                update.state = MonitoringUpdate.State.available;
                update.completedAt = clock.instant();
                repository.saveUpdate(update);
                continue;
            }
            try {
                boolean fresh = update.state == MonitoringUpdate.State.pending;
                if (fresh) {
                    delivery.validateText(update.destination, update.content);
                    update.state = MonitoringUpdate.State.sending;
                    update.dispatchedAt = clock.instant();
                    repository.saveUpdate(update);
                }
                var outcome = (fresh || update.state == MonitoringUpdate.State.sending) && allow
                        ? delivery.deliver(update.id, job.owner, update.destination, update.content)
                        : delivery.reconcile(update.id, job.owner, update.destination, update.content);
                update.state = switch (outcome.state()) {
                    case succeeded -> MonitoringUpdate.State.delivered;
                    case failed -> MonitoringUpdate.State.failed;
                    case uncertain -> MonitoringUpdate.State.uncertain;
                };
                update.receipt = outcome.receipt();
                update.failure = outcome.failure();
                update.completedAt = clock.instant();
                repository.saveUpdate(update);
            } catch (RuntimeException failure) {
                // Once sending intent exists, even a failed acknowledgement save must enter reconciliation.
                update.state = MonitoringUpdate.State.uncertain;
                update.failure = "Delivery requires receipt reconciliation; no automatic resend";
                repository.saveUpdate(update);
            }
        }
    }

    private static void verifyUpdate(MonitoringJob job, MonitoringUpdate update) {
        if (!job.id.equals(update.jobId) || !job.ownerAccountId.equals(update.ownerAccountId)) {
            throw new SecurityException("Monitoring notification account association mismatch");
        }
    }

    /** Keyset traversal remains correct while settlement removes entries from the unresolved set. */
    private Iterable<MonitoringUpdate> unresolved(String jobId) {
        return () -> new Iterator<>() {

            private String after;
            private List<MonitoringUpdate> page = List.of();
            private int index;
            private boolean done;

            public boolean hasNext() {
                if (index < page.size()) {
                    return true;
                }
                if (done) {
                    return false;
                }
                page = repository.unresolvedUpdates(jobId, after, 100);
                index = 0;
                done = page.size() < 100;
                return !page.isEmpty();
            }

            public MonitoringUpdate next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                var update = page.get(index++);
                after = update.id;
                return update;
            }
        };
    }

    private String context(MonitoringJob job) {
        var context = new LinkedHashMap<String, Object>();
        var definition = new LinkedHashMap<>(view(job));
        definition.keySet().retainAll(
                Set.of(
                        "name",
                        "original_request",
                        "objective",
                        "criteria",
                        "exclusions",
                        "sources",
                        "notification_policy",
                        "notification_instructions",
                        "completion_condition",
                        "completion_rule"));
        context.put("definition", definition);
        context.put("checkpoints", job.checkpoints);
        context.put("working_summary", job.workingSummary);
        context.put(
                "limitations",
                job.limitations.stream().limit(3).map(value -> bounded(value, 1000)).toList());
        context.put("questions", job.questions.stream().limit(3).map(value -> bounded(value, 1000)).toList());
        context.put("baseline", !job.baselineEstablished);
        var prior = new ArrayList<>(
                repository.listObservations(job.id, 0, Math.min(20, limits.results())).stream()
                        .map(this::observationView).toList());
        context.put("prior_observations", prior);
        String text = encode(context);
        while (text.length() > 90000 && !prior.isEmpty()) {
            prior.removeLast();
            text = encode(context);
        }
        if (text.length() > 100000) {
            throw new IllegalArgumentException("Monitoring context exceeds the bounded runtime request size");
        }
        return text;
    }

    private Map<String, Object> view(MonitoringJob job) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", job.id);
        result.put("revision", job.revision);
        result.put("status", job.status.name());
        result.put("name", job.name);
        result.put("original_request", job.originalRequest);
        result.put("objective", job.objective);
        result.put("criteria", job.criteria);
        result.put("exclusions", job.exclusions);
        result.put("sources", job.sources);
        result.put("timing", timingView(job.timing));
        result.put("timezone", job.timing.timezone());
        result.put("notification_policy", job.notificationPolicy.name());
        result.put("notification_instructions", job.notificationInstructions);
        result.put("initial_summary", job.initialSummary);
        result.put("initial_check", "At the next scheduled due time; use run-now for an earlier baseline");
        result.put(
                "destination",
                job.destination == null ? Map.of("transport", "console") : destinationView(job.destination));
        if (job.digestTiming != null) {
            result.put("digest_timing", timingView(job.digestTiming));
        }
        if (job.endAt != null) {
            result.put("end_at", job.endAt.toString());
        }
        if (job.completionCondition != null) {
            result.put("completion_condition", job.completionCondition);
        }
        if (!job.completionRule.isEmpty()) {
            result.put("completion_rule", job.completionRule);
        }
        result.put("health", job.health);
        result.put("questions", job.questions);
        var limitations = new ArrayList<>(job.limitations);
        if (!workerEnabled) {
            limitations.add(
                    "JobRunr background worker is disabled; periodic checks require jobrunr.background-job-server.enabled=true");
        }
        if (!gateway.get().available()) {
            limitations.add(
                    "Agent inference is unavailable; configure one inference provider before checks can run");
        }
        if (!schedules.available()) {
            limitations.add("Scheduling unavailable; enable agent scheduling and the JobRunr worker");
        }
        if (job.completionCondition != null && !job.completionCondition.isBlank()
                && job.completionRule.isEmpty()) {
            limitations.add(
                    "The natural-language completion condition requires owner verification and closure; configure an exact fact rule for automatic closure");
        }
        result.put("limitations", limitations);
        result.put("working_summary", job.workingSummary);
        result.put("schedule_id", job.scheduleId);
        result.put("next_due", job.nextDue == null ? "" : job.nextDue.toString());
        if (job.activeRunId != null) {
            result.put("active_run_id", job.activeRunId);
        }
        result.put("registration", job.registrationFailure != null ? "pending" : "registered");
        if (job.registrationFailure != null) {
            result.put("registration_failure", job.registrationFailure);
        }
        putTime(result, "created_at", job.createdAt);
        putTime(result, "updated_at", job.updatedAt);
        putTime(result, "closed_at", job.closedAt);
        putTime(result, "last_success", job.lastSuccessAt);
        putTime(result, "last_check", job.lastCheckAt);
        putTime(result, "next_digest", job.nextDigestAt);
        return result;
    }

    private Map<String, Object> runView(MonitoringRun run) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", run.id);
        row.put("job_id", run.jobId);
        row.put("execution_id", run.executionId);
        row.put("schedule_id", run.scheduleId);
        row.put("occurrence_id", run.occurrenceId);
        row.put("definition_revision", run.jobRevision);
        row.put("state", run.state.name());
        row.put("summary", run.summary);
        row.put("baseline", run.baseline);
        if (run.outcome != null) {
            row.put("outcome", run.outcome.name());
        }
        if (run.failure != null) {
            row.put("failure", run.failure);
        }
        if (run.result != null) {
            row.put("result", JSON.convertValue(run.result, Map.class));
        }
        row.put("observation_ids", run.observationIds);
        row.put("update_ids", run.updateIds);
        putTime(row, "created_at", run.createdAt);
        putTime(row, "completed_at", run.completedAt);
        return row;
    }

    private Map<String, Object> observationView(MonitoringObservation value) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", value.id);
        row.put("run_id", value.runId);
        row.put("source_id", value.sourceId);
        row.put("item_id", value.itemId);
        row.put("matched", value.matched);
        row.put("facts", value.facts);
        row.put("evidence", value.evidence);
        row.put("relevance", value.relevance);
        row.put("fingerprint", value.fingerprint);
        if (value.priorObservationId != null) {
            row.put("prior_observation_id", value.priorObservationId);
        }
        putTime(row, "observed_at", value.observedAt);
        putTime(row, "source_time", value.sourceTime);
        return row;
    }

    private Map<String, Object> updateView(MonitoringUpdate update) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", update.id);
        row.put("run_id", update.runId);
        row.put("content", update.content);
        row.put("state", update.state.name());
        row.put("observation_ids", update.observationIds);
        row.put("baseline", update.baseline);
        row.put(
                "destination",
                update.destination == null
                        ? Map.of("transport", "console")
                        : destinationView(update.destination));
        if (update.receipt != null) {
            row.put("receipt", update.receipt);
        }
        if (update.failure != null) {
            row.put("failure", update.failure);
        }
        if (update.bundledInto != null) {
            row.put("bundled_into", update.bundledInto);
        }
        putTime(row, "created_at", update.createdAt);
        putTime(row, "completed_at", update.completedAt);
        return row;
    }

    private void checkEnd(MonitoringJob job) {
        if (job.status != MonitoringJob.Status.CLOSED && job.endAt != null
                && !job.endAt.isAfter(clock.instant())) {
            job.status = MonitoringJob.Status.CLOSED;
            job.closedAt = clock.instant();
            job.revision++;
            save(job);
        }
    }

    private void save(MonitoringJob job) {
        job.updatedAt = clock.instant().isBefore(job.updatedAt) ? job.updatedAt : clock.instant();
        repository.saveJob(job);
    }

    private MonitoringJob owned(TrustedScope owner, String id) {
        var job = repository.getJob(id);
        if (!job.ownerAccountId.equals(owner.requireOwnerAccountId())) {
            throw new NotFoundException("Monitoring job not found");
        }
        return job;
    }

    private List<MonitoringJob> jobs() {
        var all = new ArrayList<MonitoringJob>();
        for (int offset = 0;; offset += 100) {
            var page = repository.allJobs(offset, 100);
            all.addAll(page);
            if (page.size() < 100) {
                return all;
            }
        }
    }

    private static void actor(TrustedScope owner) {
        Objects.requireNonNull(owner, "Trusted account scope").requireOwnerAccountId();
    }

    private static void editable(MonitoringJob job) {
        if (job.status == MonitoringJob.Status.CLOSED) {
            throw new ConflictException("CLOSED monitoring jobs are terminal");
        }
    }

    private static void revision(MonitoringJob job, long expected) {
        if (expected < 1 || expected != job.revision) {
            throw new ConflictException("Stale monitoring revision; reload before changing it");
        }
    }

    private static String scheduleId(String id) {
        return "monitor_" + UUID.fromString(id);
    }

    private static String identity(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static TrustedScope schedulerScope(TrustedScope owner) {
        return owner.withOrigin("scheduler");
    }

    private void finishOccurrence(
            ScheduleRecord.Occurrence occurrence,
            ScheduleRecord.Outcome outcome,
            String failure) {
        occurrence.outcome = outcome;
        occurrence.failure = failure;
        occurrence.completedAt = clock.instant();
    }

    /**
     * The agent's final report. A check that ended {@code partial} reached a budget boundary and reported
     * through the tools-disabled reporting turn; a valid report from it is accepted with the boundary
     * recorded as a limitation and never counts as success. Any other terminal status, or a final answer
     * that is not the JSON contract, fails the run and names the boundary.
     */
    private MonitoringResult reportedResult(ExecutionSnapshot execution) {
        boolean bounded = execution.status == ExecutionStatus.partial;
        if (execution.status != ExecutionStatus.succeeded && !bounded) {
            return failed("Agent execution ended with status " + execution.status.name());
        }
        String boundary = Objects.requireNonNullElse(execution.failure, "budget");
        MonitoringResult parsed;
        try {
            parsed = new MonitoringResultParser()
                    .parse(execution.finalAnswer, limits.results(), limits.results(), 8000);
        } catch (IllegalArgumentException invalid) {
            return failed(
                    "The agent returned an invalid structured monitoring result"
                            + (bounded ? " after stopping at the " + boundary + " boundary" : "")
                            + "; no findings or updates were accepted");
        }
        if (!bounded) {
            return parsed;
        }
        var limitations = new ArrayList<>(parsed.limitations());
        if (limitations.size() < 30) {
            limitations.add(
                    "The check stopped at the " + boundary
                            + " boundary before the agent could confirm completion");
        }
        return new MonitoringResult(
                parsed.outcome() == MonitoringResult.Outcome.success
                        ? MonitoringResult.Outcome.partial
                        : parsed.outcome(),
                parsed.summary(),
                parsed.observations(),
                parsed.updates(),
                parsed.coverage(),
                parsed.freshness(),
                parsed.checkpoints(),
                parsed.questions(),
                limitations,
                parsed.completionEvidence());
    }

    private static MonitoringResult failed(String message) {
        return new MonitoringResult(
                MonitoringResult.Outcome.failed,
                message,
                List.of(),
                List.of(),
                List.of(),
                "Unverified",
                Map.of(),
                List.of(),
                List.of(message),
                List.of());
    }

    /**
     * A finding's {@code prior_observation_id} is advisory: the stored prior link always comes from the
     * latest saved observation for the same job, source and item. A claim that names anything else is
     * dropped for that finding and reported as a limitation instead of rejecting the whole result, and
     * nothing about records outside this job is disclosed.
     */
    private String ignoredPriorClaim(MonitoringJob job, MonitoringResult.Observation finding) {
        String claim = finding.priorObservationId();
        if (claim == null || claim.isBlank()) {
            return null;
        }
        MonitoringObservation claimed;
        try {
            claimed = repository.getObservation(claim);
        } catch (NotFoundException | IllegalArgumentException unknown) {
            claimed = null;
        }
        String reason;
        if (claimed == null || !job.ownerAccountId.equals(claimed.ownerAccountId)
                || !job.id.equals(claimed.jobId)) {
            reason = "it is not a stored observation of this monitoring job";
        } else if (!finding.sourceId().equals(claimed.sourceId) || !finding.itemId().equals(claimed.itemId)) {
            reason = "it belongs to a different monitoring item";
        } else {
            return null;
        }
        return "Ignored prior_observation_id " + claim + " on observation " + finding.key() + ": " + reason;
    }

    private static MonitoringResult withLimitations(MonitoringResult result, List<String> limitations) {
        return new MonitoringResult(
                result.outcome(),
                result.summary(),
                result.observations(),
                result.updates(),
                result.coverage(),
                result.freshness(),
                result.checkpoints(),
                result.questions(),
                List.copyOf(limitations),
                result.completionEvidence());
    }

    /** Names the planning failure so the console shows its cause, bounded like a reported limitation. */
    private static String rejected(RuntimeException failure) {
        String reason = failure.getMessage() == null || failure.getMessage().isBlank()
                ? failure.getClass().getSimpleName()
                : failure.getMessage().strip();
        String summary = "Monitoring result was rejected; no observations or updates were accepted: "
                + reason;
        return summary.length() <= 2000 ? summary : summary.substring(0, 2000);
    }

    private static Map<String, Object> timingView(ScheduleTiming timing) {
        return Map.of("cron", timing.cron(), "timezone", timing.timezone());
    }

    private static Map<String, Object> destinationView(ScheduleDestination destination) {
        var value = new LinkedHashMap<String, Object>();
        value.put("transport", destination.transport());
        value.put("channel_id", destination.channelId());
        if (destination.threadId() != null) {
            value.put("thread_ts", destination.threadId());
        }
        return value;
    }

    private static void putTime(Map<String, Object> map, String field, Instant value) {
        if (value != null) {
            map.put(field, value.toString());
        }
    }

    private static String text(Map<String, Object> input, String key, String fallback) {
        return input.containsKey(key) ? (String) input.get(key) : fallback;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    private static String encode(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalArgumentException("Monitoring context could not be encoded", failure);
        }
    }

    private static String bounded(String value, int max) {
        return value.length() <= max
                ? value
                : value.substring(0, max - 80)
                        + "\n[Preview shortened; inspect the saved run and evidence in the console.]";
    }

    private static void page(int offset, int limit) {
        if (offset < 0 || offset > 1000000 || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Invalid monitoring page");
        }
    }

    private static Map<String, Object> pageView(
            String field,
            List<?> values,
            int offset,
            int limit,
            boolean more) {
        return Map.of(
                "status",
                "ok",
                field,
                values,
                "offset",
                offset,
                "limit",
                limit,
                "has_more",
                more,
                "next_offset",
                more ? offset + limit : -1);
    }
}
