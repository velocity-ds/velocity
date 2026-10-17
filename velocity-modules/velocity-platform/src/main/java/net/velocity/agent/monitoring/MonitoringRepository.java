package net.velocity.agent.monitoring;

import java.time.Instant;
import java.util.List;

import net.velocity.agent.state.TrustedScope;

/** Detached, bounded snapshots. Mutable saves compare the caller's storage version atomically. */
public interface MonitoringRepository {

    MonitoringJob getJob(String id);

    void saveJob(MonitoringJob job);

    List<MonitoringJob> listJobs(TrustedScope owner, int offset, int limit);

    List<MonitoringJob> allJobs(int offset, int limit);

    MonitoringRun getRun(String id);

    void saveRun(MonitoringRun run);

    List<MonitoringRun> listRuns(String jobId, int offset, int limit);

    int activeRunCount();

    MonitoringObservation getObservation(String id);

    void saveObservation(MonitoringObservation observation);

    List<MonitoringObservation> listObservations(String jobId, int offset, int limit);

    /** Returns null when the item has never been observed. */
    MonitoringObservation latestObservation(String jobId, String sourceId, String itemId);

    MonitoringUpdate getUpdate(String id);

    void saveUpdate(MonitoringUpdate update);

    List<MonitoringUpdate> listUpdates(String jobId, int offset, int limit);

    /** Includes uncertain sends, which must remain pinned and must not be replayed. */
    List<MonitoringUpdate> pendingUpdates(String jobId);

    /** Stable ID keyset pagination so settling earlier entries does not skip later unresolved work. */
    List<MonitoringUpdate> unresolvedUpdates(String jobId, String afterId, int limit);

    /** Removes only old settled history; retains current item versions and all unresolved references. */
    void prune(String jobId, Instant before, int retainLatestRuns);
}
