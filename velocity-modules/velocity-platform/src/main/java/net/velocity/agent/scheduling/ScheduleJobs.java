package net.velocity.agent.scheduling;

import java.time.Instant;

/** Queue adapter; all dispatch authority remains in the saved schedule. */
public interface ScheduleJobs {

    boolean available();

    void ensure(String scheduleId, String occurrenceId, Instant dueAt);

    default void reconcilePeriodically() {
    }
}
