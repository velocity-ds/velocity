package net.velocity.agent.scheduling;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

import org.jobrunr.scheduling.JobScheduler;
import org.jobrunr.storage.StorageProvider;

/** Idempotent queue registration, including jobs completed before local settlement was persisted. */
public final class JobRunrScheduleJobs implements ScheduleJobs {

    private static final String UNAVAILABLE = "Scheduling requires an enabled JobRunr background server";
    private final JobRunrQueue queue;
    private final boolean enabled;

    public JobRunrScheduleJobs(
            boolean enabled,
            Supplier<JobScheduler> scheduler,
            Supplier<StorageProvider> storage) {
        this.enabled = enabled;
        this.queue = new JobRunrQueue(scheduler, storage, UNAVAILABLE);
    }

    @Override
    public boolean available() {
        return enabled && queue.available();
    }

    @Override
    public synchronized void ensure(String scheduleId, String occurrenceId, Instant dueAt) {
        if (!available()) {
            throw new IllegalStateException(UNAVAILABLE);
        }
        UUID jobId = UUID.fromString(occurrenceId);
        queue.ensureScheduled(
                jobId,
                dueAt,
                "Reconcile saved schedule occurrence",
                jobs -> jobs.<ScheduleService>schedule(
                        jobId,
                        dueAt,
                        service -> service.dispatch(scheduleId, occurrenceId)));
    }

    @Override
    public void reconcilePeriodically() {
        if (!available()) {
            return;
        }
        // A queue that vanishes after the availability check must fail so bootstrap recovery retries.
        boolean registered = queue.registerRecurring(jobs -> {
            jobs.<ScheduleService>scheduleRecurrently(
                    "velocity-schedule-reconciliation",
                    Duration.ofSeconds(30),
                    ScheduleService::reconcile);
            jobs.<ScheduleService>enqueue(ScheduleService::reconcile);
        });
        if (!registered) {
            throw new IllegalStateException(UNAVAILABLE);
        }
    }
}
