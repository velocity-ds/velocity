package net.velocity.agent.scheduling;

/** Trusted bridge to monitoring state; queue payloads cannot supply monitoring authority. */
public interface MonitoringScheduleHandler {

    /**
     * Runs under the schedule lock. Mutate the occurrence with its settled result; do not
     * replace this schedule through upsertMonitoring while its detached snapshot is loaded.
     */
    void refresh(ScheduleRecord schedule, ScheduleRecord.Occurrence occurrence, boolean allowAdmission);

    /** Repairs job/definition/delivery gaps before schedule locks are acquired. */
    default void reconcile() {
    }
}
