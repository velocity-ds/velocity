package net.velocity.agent.scheduling;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.jobrunr.scheduling.JobScheduler;
import org.jobrunr.storage.JobNotFoundException;
import org.jobrunr.storage.StorageProvider;
import org.springframework.core.env.Environment;

/**
 * One feature's view of the shared JobRunr scheduler and storage. Callers retain their job identities,
 * callback bindings, feature flags and durable domain records; this class only owns queue plumbing.
 * Each instance coordinates one application process and is not a distributed lock.
 */
public final class JobRunrQueue {

    private final Supplier<JobScheduler> scheduler;
    private final Supplier<StorageProvider> storage;
    private final String unavailableMessage;

    public JobRunrQueue(
            Supplier<JobScheduler> scheduler,
            Supplier<StorageProvider> storage,
            String unavailableMessage) {
        this.scheduler = scheduler;
        this.storage = storage;
        this.unavailableMessage = unavailableMessage;
    }

    /** JobRunr defaults its background worker to disabled; agent scheduling is a separate switch. */
    public static boolean workerEnabled(Environment environment) {
        return environment.getProperty("jobrunr.background-job-server.enabled", Boolean.class, false);
    }

    public boolean available() {
        return scheduler.get() != null && storage.get() != null;
    }

    /**
     * Idempotently ensures the occurrence job exists under the caller's persisted identity: scheduled,
     * enqueued and processing jobs are untouched, terminal jobs are rescheduled with their original
     * callback, and a missing job is created by {@code createMissing}.
     */
    public synchronized void ensureScheduled(
            UUID id,
            Instant due,
            String repairReason,
            Consumer<JobScheduler> createMissing) {
        var jobs = scheduler.get();
        var database = storage.get();
        if (jobs == null || database == null) {
            throw new IllegalStateException(unavailableMessage);
        }
        try {
            var job = database.getJobById(id);
            switch (job.getState()) {
                case SCHEDULED, ENQUEUED, PROCESSING -> {
                    return;
                }
                default -> {
                    job.scheduleAt(due, repairReason);
                    database.save(job);
                }
            }
        } catch (JobNotFoundException missing) {
            createMissing.accept(jobs);
        }
    }

    /** Runs the caller's recurring registration; returns false while the scheduler or storage is absent. */
    public boolean registerRecurring(Consumer<JobScheduler> register) {
        var jobs = scheduler.get();
        if (jobs == null || storage.get() == null) {
            return false;
        }
        register.accept(jobs);
        return true;
    }

    /** Removes a recurring definition if the scheduler exists; a missing scheduler has nothing to remove. */
    public void retireRecurring(String recurringJobId) {
        var jobs = scheduler.get();
        if (jobs != null) {
            jobs.deleteRecurringJob(recurringJobId);
        }
    }
}
