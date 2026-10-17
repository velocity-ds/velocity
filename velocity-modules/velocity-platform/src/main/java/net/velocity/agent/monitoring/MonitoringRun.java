package net.velocity.agent.monitoring;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** One bounded check, bound to a saved schedule occurrence and execution by the server. */
public final class MonitoringRun {

    public int formatVersion = 2;
    public String id;
    public String ownerAccountId;
    public long storageVersion;
    public String jobId;
    public String scheduleId;
    public String occurrenceId;
    public String executionId;
    public long jobRevision;
    public long criteriaVersion;
    public MonitoringJob definition;
    public String prompt;
    public State state = State.prepared;
    public MonitoringResult.Outcome outcome;
    public Instant createdAt;
    public Instant completedAt;
    public String summary = "";
    public String failure;
    public MonitoringResult result;
    public List<String> observationIds = new ArrayList<>();
    public List<String> updateIds = new ArrayList<>();
    public List<MonitoringObservation> observationPlan = new ArrayList<>();
    public List<MonitoringUpdate> updatePlan = new ArrayList<>();
    public boolean planPrepared;
    public boolean jobApplied;
    public boolean baseline;

    public enum State {
        prepared, running, finalizing, completed, cancelled
    }
}
