package net.velocity.agent.scheduling;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import net.velocity.agent.state.TrustedScope;

/** Durable schedule definition. Only trusted ingress may supply the owner. */
public final class ScheduleRecord {

    public int formatVersion = 2;
    public String id;
    public String name;
    public long revision = 1;
    public Status status = Status.active;
    public Action action;
    public String prompt;
    public String message;
    public TrustedScope owner;
    public String ownerAccountId;
    public ScheduleTiming timing;
    public ScheduleDestination destination;
    /** Optional server-owned association; absent on ordinary schedules. */
    public String monitoringJobId;
    public long monitoringRevision;
    public Instant nextOccurrence;
    public Instant createdAt;
    public Instant updatedAt;
    public String registrationFailure;
    public Map<String, Occurrence> occurrences = new LinkedHashMap<>();

    public enum Status {
        active, paused, cancelled, completed
    }

    public enum Action {
        execution, message
    }

    public enum Outcome {
        pending, running, succeeded, failed, uncertain, skipped, cancelled
    }

    /** A stable identity and definition revision are committed before a job is registered. */
    public static final class Occurrence {

        public String id;
        public String ownerAccountId;
        public long revision;
        public long monitoringRevision;
        public Instant dueAt;
        public Outcome outcome = Outcome.pending;
        public ScheduleDestination destination;
        public String prompt;
        public String message;
        public String executionId;
        public String executionStatus;
        public String receipt;
        public String failure;
        public Instant completedAt;
    }
}
