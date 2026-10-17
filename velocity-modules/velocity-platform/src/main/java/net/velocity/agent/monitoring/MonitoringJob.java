package net.velocity.agent.monitoring;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.velocity.agent.scheduling.ScheduleDestination;
import net.velocity.agent.scheduling.ScheduleTiming;
import net.velocity.agent.state.TrustedScope;

/** Durable user objective. Its owner is supplied only by trusted ingress. */
public final class MonitoringJob {

    public int formatVersion = 2;
    public String id;
    public String ownerAccountId;
    public long storageVersion;
    public long revision = 1;
    public long criteriaVersion = 1;
    public TrustedScope owner;
    public String name;
    public String originalRequest;
    public String objective;
    public String criteria = "";
    public String exclusions = "";
    public String sources = "";
    public ScheduleTiming timing;
    public NotificationPolicy notificationPolicy = NotificationPolicy.changes_only;
    public ScheduleTiming digestTiming;
    public String notificationInstructions = "";
    public boolean initialSummary;
    public ScheduleDestination destination;
    public Instant endAt;
    public String completionCondition;
    public Map<String, String> completionRule = new LinkedHashMap<>();
    public Status status = Status.DRAFT;
    public String health = "not_checked";
    public String registrationFailure;
    public Instant createdAt;
    public Instant updatedAt;
    public Instant closedAt;
    public String scheduleId;
    public String activeRunId;
    public String lastAppliedRunId;
    public Instant lastCheckAt;
    public Instant lastSuccessAt;
    public Instant nextDue;
    public Instant lastDigestAt;
    public Instant nextDigestAt;
    public boolean baselineEstablished;
    public String workingSummary = "";
    public List<String> questions = new ArrayList<>();
    public List<String> limitations = new ArrayList<>();
    public Map<String, String> checkpoints = new LinkedHashMap<>();

    public enum Status {
        DRAFT, ACTIVE, PAUSED, NEEDS_INPUT, CLOSED
    }

    public enum NotificationPolicy {
        changes_only, digest
    }
}
