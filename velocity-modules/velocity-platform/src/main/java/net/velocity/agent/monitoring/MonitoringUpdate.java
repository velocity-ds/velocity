package net.velocity.agent.monitoring;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import net.velocity.agent.scheduling.ScheduleDestination;

/** Saved presentation content and delivery intent; observations never imply successful delivery. */
public final class MonitoringUpdate {

    public int formatVersion = 2;
    public String id;
    public String ownerAccountId;
    public long storageVersion;
    public String jobId;
    public String runId;
    public String content;
    public List<String> observationIds = new ArrayList<>();
    public List<String> sourceUpdateIds = new ArrayList<>();
    public String bundledInto;
    public ScheduleDestination destination;
    public State state = State.pending;
    public String receipt;
    public String failure;
    public Instant createdAt;
    public Instant dispatchedAt;
    public Instant completedAt;
    public boolean baseline;

    public enum State {
        queued, pending, sending, available, delivered, failed, uncertain, cancelled, bundled
    }
}
