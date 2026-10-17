package net.velocity.agent.monitoring;

import java.io.IOException;
import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.velocity.agent.persistence.ConflictException;
import net.velocity.agent.persistence.CorruptStateException;
import net.velocity.agent.persistence.PersistedJson;

/** Detached snapshots with explicit format versions and nanosecond timestamp precision. */
public final class MonitoringRecordCodec {

    private static final ObjectMapper DEFINITION_JSON = PersistedJson.builder().build();

    final ObjectMapper mapper;

    public MonitoringRecordCodec() {
        mapper = PersistedJson.builder().build();
    }

    public <T> T copy(T value, Class<T> type) {
        return decode(encode(value), type);
    }

    public MonitoringJob copy(MonitoringJob value) {
        return copy(value, MonitoringJob.class);
    }

    public MonitoringRun copy(MonitoringRun value) {
        return copy(value, MonitoringRun.class);
    }

    public MonitoringObservation copy(MonitoringObservation value) {
        return copy(value, MonitoringObservation.class);
    }

    public MonitoringUpdate copy(MonitoringUpdate value) {
        return copy(value, MonitoringUpdate.class);
    }

    byte[] encode(Object value) {
        validate(value);
        try {
            byte[] bytes = mapper.writeValueAsBytes(value);
            if (bytes.length > 12_000_000) {
                throw new CorruptStateException("Monitoring record exceeds its size limit");
            }
            return bytes;
        } catch (IOException failure) {
            throw new CorruptStateException("Monitoring record cannot be encoded", failure);
        }
    }

    <T> T decode(byte[] bytes, Class<T> type) {
        try {
            JsonNode tree = mapper.readTree(bytes);
            if (tree == null || !tree.isObject() || !tree.has("formatVersion")
                    || !tree.get("formatVersion").isIntegralNumber()
                    || tree.get("formatVersion").intValue() != 2) {
                throw new CorruptStateException("Missing or unsupported monitoring format version");
            }
            required(tree, "id", "ownerAccountId");
            if (type == MonitoringJob.class) {
                required(
                        tree,
                        "storageVersion",
                        "revision",
                        "criteriaVersion",
                        "owner",
                        "name",
                        "originalRequest",
                        "objective",
                        "timing",
                        "notificationPolicy",
                        "status",
                        "createdAt",
                        "updatedAt");
            } else if (type == MonitoringRun.class) {
                required(
                        tree,
                        "storageVersion",
                        "jobId",
                        "scheduleId",
                        "occurrenceId",
                        "executionId",
                        "jobRevision",
                        "criteriaVersion",
                        "state",
                        "createdAt",
                        "observationIds",
                        "updateIds");
            } else if (type == MonitoringObservation.class) {
                required(
                        tree,
                        "jobId",
                        "runId",
                        "sourceId",
                        "itemId",
                        "fingerprint",
                        "observedAt",
                        "facts",
                        "evidence",
                        "criteriaVersion");
            } else if (type == MonitoringUpdate.class) {
                required(
                        tree,
                        "storageVersion",
                        "jobId",
                        "runId",
                        "content",
                        "observationIds",
                        "sourceUpdateIds",
                        "state",
                        "createdAt");
            }
            T value = mapper.treeToValue(tree, type);
            validate(value);
            return value;
        } catch (IOException | IllegalArgumentException failure) {
            throw new CorruptStateException("Corrupt monitoring state", failure);
        }
    }

    static String id(String id) {
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")) {
            throw new IllegalArgumentException("Invalid monitoring identifier");
        }
        return id;
    }

    static void validateReplacement(Object before, Object after) {
        if (before instanceof MonitoringJob a && after instanceof MonitoringJob b) {
            if (!a.owner.equals(b.owner) || !a.ownerAccountId.equals(b.ownerAccountId)
                    || !a.createdAt.equals(b.createdAt) || !a.originalRequest.equals(b.originalRequest)) {
                throw new ConflictException(
                        "Monitoring ownership, original request and creation time are immutable");
            }
            if (b.revision < a.revision || b.criteriaVersion < a.criteriaVersion
                    || a.status == MonitoringJob.Status.CLOSED && b.status != MonitoringJob.Status.CLOSED) {
                throw new ConflictException(
                        "Monitoring revisions cannot move backwards and closed jobs are terminal");
            }
        } else if (before instanceof MonitoringRun a && after instanceof MonitoringRun b) {
            if (!a.ownerAccountId.equals(b.ownerAccountId) || !Objects.equals(a.jobId, b.jobId)
                    || !Objects.equals(a.scheduleId, b.scheduleId)
                    || !Objects.equals(a.occurrenceId, b.occurrenceId)
                    || !Objects.equals(a.executionId, b.executionId)
                    || a.jobRevision != b.jobRevision || a.criteriaVersion != b.criteriaVersion
                    || !Objects.equals(a.createdAt, b.createdAt) || !Objects.equals(a.prompt, b.prompt)) {
                throw new ConflictException("Monitoring run binding and prepared context are immutable");
            }
            if (!sameDefinition(a.definition, b.definition)) {
                throw new ConflictException("Monitoring run captured definition is immutable");
            }
        } else if (before instanceof MonitoringUpdate a && after instanceof MonitoringUpdate b) {
            if (!a.ownerAccountId.equals(b.ownerAccountId) || !Objects.equals(a.jobId, b.jobId)
                    || !Objects.equals(a.runId, b.runId)
                    || !Objects.equals(a.content, b.content) || !Objects.equals(a.destination, b.destination)
                    || !Objects.equals(a.observationIds, b.observationIds)
                    || !Objects.equals(a.sourceUpdateIds, b.sourceUpdateIds)
                    || !Objects.equals(a.createdAt, b.createdAt)) {
                throw new ConflictException(
                        "Monitoring update content, references and destination are immutable");
            }
            if (a.state == MonitoringUpdate.State.uncertain && b.state != a.state
                    && !(b.state == MonitoringUpdate.State.delivered && b.receipt != null
                            && !b.receipt.isBlank())) {
                throw new ConflictException(
                        "Uncertain monitoring notifications require a positive receipt to settle");
            }
            if ((a.state == MonitoringUpdate.State.delivered || a.state == MonitoringUpdate.State.available)
                    && b.state != a.state) {
                throw new ConflictException(
                        "Settled or uncertain monitoring notifications cannot be replayed");
            }
        }
    }

    private static boolean sameDefinition(MonitoringJob before, MonitoringJob after) {
        return DEFINITION_JSON.valueToTree(before).equals(DEFINITION_JSON.valueToTree(after));
    }

    private static void validate(Object value) {
        String ownerAccountId = switch (value) {
            case MonitoringJob job -> job.ownerAccountId;
            case MonitoringRun run -> run.ownerAccountId;
            case MonitoringObservation observation -> observation.ownerAccountId;
            case MonitoringUpdate update -> update.ownerAccountId;
            default -> throw new CorruptStateException("Unsupported monitoring record");
        };
        try {
            java.util.UUID.fromString(ownerAccountId);
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new CorruptStateException("Invalid monitoring account", failure);
        }
        if (value instanceof MonitoringJob job) {
            id(job.id);
            if (job.formatVersion != 2 || job.storageVersion < 0 || job.revision < 1
                    || job.criteriaVersion < 1
                    || job.owner == null || blank(job.name) || blank(job.originalRequest)
                    || blank(job.objective)
                    || !job.ownerAccountId.equals(job.owner.ownerAccountId())
                    || job.status == null || job.notificationPolicy == null || job.timing == null
                    || job.createdAt == null || job.updatedAt == null || job.updatedAt.isBefore(job.createdAt)
                    || job.questions == null || job.limitations == null || job.checkpoints == null
                    || job.completionRule == null || job.workingSummary == null) {
                throw new CorruptStateException("Invalid monitoring definition");
            }
            if (job.scheduleId != null) {
                id(job.scheduleId);
            }
            if (job.activeRunId != null) {
                id(job.activeRunId);
            }
            if (job.lastAppliedRunId != null) {
                id(job.lastAppliedRunId);
            }
        } else if (value instanceof MonitoringRun run) {
            id(run.id);
            id(run.jobId);
            id(run.scheduleId);
            id(run.occurrenceId);
            id(run.executionId);
            if (run.formatVersion != 2 || run.storageVersion < 0 || run.jobRevision < 1
                    || run.criteriaVersion < 1
                    || run.state == null || run.createdAt == null || run.observationIds == null
                    || run.updateIds == null
                    || run.observationPlan == null || run.updatePlan == null) {
                throw new CorruptStateException("Invalid monitoring run");
            }
            if (run.definition != null && (!run.ownerAccountId.equals(run.definition.ownerAccountId)
                    || !run.jobId.equals(run.definition.id) || run.jobRevision != run.definition.revision)) {
                throw new CorruptStateException("Monitoring run definition does not match its binding");
            }
            if (run.definition != null) {
                validate(run.definition);
            }
            for (MonitoringObservation observation : run.observationPlan) {
                validate(observation);
                if (!run.ownerAccountId.equals(observation.ownerAccountId)
                        || !run.id.equals(observation.runId) || !run.jobId.equals(observation.jobId)) {
                    throw new CorruptStateException("Monitoring observation plan belongs to another run");
                }
            }
            for (MonitoringUpdate update : run.updatePlan) {
                validate(update);
                if (!run.ownerAccountId.equals(update.ownerAccountId)
                        || !run.id.equals(update.runId) || !run.jobId.equals(update.jobId)) {
                    throw new CorruptStateException("Monitoring update plan belongs to another run");
                }
            }
        } else if (value instanceof MonitoringObservation observation) {
            id(observation.id);
            id(observation.jobId);
            id(observation.runId);
            if (observation.formatVersion != 2 || blank(observation.sourceId) || blank(observation.itemId)
                    || observation.fingerprint == null || !observation.fingerprint.matches("[a-f0-9]{64}")
                    || observation.observedAt == null || observation.facts == null
                    || observation.facts.isEmpty()
                    || observation.evidence == null || observation.evidence.isEmpty()
                    || observation.criteriaVersion < 1) {
                throw new CorruptStateException("Invalid monitoring observation");
            }
            if (!observation.fingerprint.equals(ObservationFingerprint.fingerprint(observation.facts))
                    && !observation.fingerprint.equals(
                            ObservationFingerprint.fingerprint(
                                    observation.sourceId,
                                    observation.itemId,
                                    observation.facts))) {
                throw new CorruptStateException(
                        "Monitoring observation facts do not match the saved fingerprint");
            }
            if (observation.priorObservationId != null) {
                id(observation.priorObservationId);
            }
        } else if (value instanceof MonitoringUpdate update) {
            id(update.id);
            id(update.jobId);
            id(update.runId);
            if (update.formatVersion != 2 || update.storageVersion < 0 || blank(update.content)
                    || update.observationIds == null || update.sourceUpdateIds == null
                    || update.state == null || update.createdAt == null) {
                throw new CorruptStateException("Invalid monitoring update");
            }
            update.observationIds.forEach(MonitoringRecordCodec::id);
            update.sourceUpdateIds.forEach(MonitoringRecordCodec::id);
        } else {
            throw new CorruptStateException("Unsupported monitoring record");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static void required(JsonNode tree, String... fields) {
        for (String field : fields) {
            if (!tree.hasNonNull(field)) {
                throw new CorruptStateException("Missing monitoring field: " + field);
            }
        }
    }
}
