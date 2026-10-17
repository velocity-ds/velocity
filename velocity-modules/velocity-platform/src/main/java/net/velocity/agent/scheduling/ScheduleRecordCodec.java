package net.velocity.agent.scheduling;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.velocity.agent.SchedulerIngressGateway;
import net.velocity.agent.persistence.ConflictException;
import net.velocity.agent.persistence.CorruptStateException;
import net.velocity.agent.persistence.PersistedJson;

/** Strict encoding for schedule snapshots and MongoDB records. Timestamps retain nanoseconds. */
final class ScheduleRecordCodec {

    private static final int VERSION = 2;
    private final ObjectMapper mapper;

    ScheduleRecordCodec() {
        mapper = PersistedJson.builder().build();
    }

    byte[] encode(ScheduleRecord value) {
        Objects.requireNonNull(value, "Schedule");
        validate(value, name(value.id));
        try {
            return mapper.writeValueAsBytes(value);
        } catch (IOException | IllegalArgumentException failure) {
            throw new CorruptStateException("Schedule cannot be encoded", failure);
        }
    }

    ScheduleRecord copy(ScheduleRecord value) {
        return decode(encode(value), value.id);
    }

    ScheduleRecord decode(byte[] bytes, String id) {
        try {
            JsonNode json = mapper.readTree(bytes);
            if (json == null || !json.isObject() || !json.has("formatVersion")
                    || !json.get("formatVersion").isIntegralNumber()
                    || !json.get("formatVersion").canConvertToInt()
                    || json.get("formatVersion").intValue() != VERSION) {
                throw new CorruptStateException("Unsupported or missing schedule format version: " + id);
            }
            for (String field : List.of(
                    "id",
                    "name",
                    "revision",
                    "status",
                    "action",
                    "owner",
                    "ownerAccountId",
                    "timing",
                    "createdAt",
                    "updatedAt",
                    "occurrences")) {
                if (!json.hasNonNull(field)) {
                    throw new CorruptStateException("Missing schedule field: " + field);
                }
            }
            if (!json.has("nextOccurrence")) {
                throw new CorruptStateException("Missing schedule next occurrence");
            }
            JsonNode timing = json.get("timing");
            if (!timing.isObject() || !timing.hasNonNull("mode") || !timing.hasNonNull("timezone")) {
                throw new CorruptStateException("Missing normalized schedule timing or timezone");
            }
            if (!json.get("occurrences").isObject()) {
                throw new CorruptStateException("Invalid schedule occurrences");
            }
            for (JsonNode occurrence : json.get("occurrences")) {
                for (String field : List.of("id", "ownerAccountId", "revision", "dueAt", "outcome")) {
                    if (!occurrence.hasNonNull(field)) {
                        throw new CorruptStateException("Missing occurrence field: " + field);
                    }
                }
            }
            ScheduleRecord schedule = mapper.treeToValue(json, ScheduleRecord.class);
            validate(schedule, id);
            return schedule;
        } catch (IOException | IllegalArgumentException failure) {
            throw new CorruptStateException("Corrupt schedule state: " + id, failure);
        }
    }

    static void validateReplacement(ScheduleRecord before, ScheduleRecord saved) {
        if (!before.owner.equals(saved.owner) || !before.ownerAccountId.equals(saved.ownerAccountId)
                || !before.createdAt.equals(saved.createdAt)
                || before.action != saved.action
                || !Objects.equals(before.monitoringJobId, saved.monitoringJobId)) {
            throw new ConflictException("Schedule owner, creation time, and action type are immutable");
        }
        if (saved.revision < before.revision) {
            throw new ConflictException("Schedule revision cannot move backwards");
        }
        if (saved.monitoringRevision < before.monitoringRevision) {
            throw new ConflictException("Monitoring revision cannot move backwards");
        }
        for (var entry : before.occurrences.entrySet()) {
            var replacement = saved.occurrences.get(entry.getKey());
            var original = entry.getValue();
            if (replacement != null && (!original.ownerAccountId.equals(replacement.ownerAccountId)
                    || original.revision != replacement.revision
                    || original.monitoringRevision != replacement.monitoringRevision
                    || !original.dueAt.equals(replacement.dueAt)
                    || original.executionId != null && !original.executionId.equals(replacement.executionId)
                    || original.prompt != null && !original.prompt.equals(replacement.prompt)
                    || original.message != null && !original.message.equals(replacement.message))) {
                throw new ConflictException("Saved occurrence authority and admitted payload are immutable");
            }
        }
    }

    private static void validate(ScheduleRecord state, String id) {
        if (state == null || state.formatVersion != VERSION || !id.equals(state.id)
                || state.name == null || state.name.isBlank() || state.revision < 1 || state.status == null
                || state.action == null || state.owner == null || state.ownerAccountId == null
                || !state.ownerAccountId.equals(state.owner.ownerAccountId()) || state.timing == null
                || state.createdAt == null || state.updatedAt == null
                || state.updatedAt.isBefore(state.createdAt)
                || state.occurrences == null) {
            throw new CorruptStateException("Invalid schedule: " + id);
        }
        try {
            java.util.UUID.fromString(state.ownerAccountId);
        } catch (IllegalArgumentException failure) {
            throw new CorruptStateException("Invalid schedule account", failure);
        }
        if (state.action == ScheduleRecord.Action.execution
                ? state.prompt == null || state.prompt.isBlank() || state.message != null
                : state.message == null || state.message.isBlank() || state.prompt != null
                        || state.destination == null) {
            throw new CorruptStateException("Invalid schedule action payload: " + id);
        }
        if (state.monitoringJobId != null) {
            try {
                name(state.monitoringJobId);
            } catch (IllegalArgumentException failure) {
                throw new CorruptStateException("Invalid monitoring association", failure);
            }
            if (state.monitoringRevision < 1 || state.action != ScheduleRecord.Action.execution
                    || state.destination != null) {
                throw new CorruptStateException("Invalid monitoring schedule: " + id);
            }
        } else if (state.monitoringRevision != 0) {
            throw new CorruptStateException("Monitoring revision without association: " + id);
        }
        int pending = 0;
        int running = 0;
        for (var entry : state.occurrences.entrySet()) {
            var occurrence = entry.getValue();
            if (occurrence == null || entry.getKey() == null || !entry.getKey().equals(occurrence.id)
                    || !state.ownerAccountId.equals(occurrence.ownerAccountId)
                    || occurrence.revision < 1 || occurrence.revision > state.revision
                    || occurrence.dueAt == null || occurrence.outcome == null) {
                throw new CorruptStateException("Invalid occurrence: " + id);
            }
            if (state.monitoringJobId == null
                    ? occurrence.monitoringRevision != 0
                    : occurrence.monitoringRevision < 1
                            || occurrence.monitoringRevision > state.monitoringRevision
                            || occurrence.destination != null) {
                throw new CorruptStateException("Invalid monitoring occurrence association: " + id);
            }
            try {
                name(occurrence.id);
            } catch (IllegalArgumentException failure) {
                throw new CorruptStateException("Invalid occurrence ID", failure);
            }
            if (occurrence.executionId != null && (state.action != ScheduleRecord.Action.execution
                    || !SchedulerIngressGateway.executionId(id, occurrence.id)
                            .equals(occurrence.executionId))) {
                throw new CorruptStateException("Occurrence execution identity mismatch: " + id);
            }
            if (occurrence.outcome == ScheduleRecord.Outcome.pending) {
                pending++;
                if (state.status != ScheduleRecord.Status.active || occurrence.revision != state.revision
                        || !occurrence.dueAt.equals(state.nextOccurrence)) {
                    throw new CorruptStateException(
                            "Pending occurrence does not match the current schedule: " + id);
                }
            }
            if (occurrence.outcome == ScheduleRecord.Outcome.running) {
                running++;
                if (state.action == ScheduleRecord.Action.execution
                        ? occurrence.prompt == null || occurrence.prompt.isBlank()
                                || occurrence.message != null
                                || occurrence.executionId == null
                        : occurrence.message == null || occurrence.message.isBlank()
                                || occurrence.prompt != null
                                || occurrence.destination == null) {
                    throw new CorruptStateException("Running occurrence lacks its saved action: " + id);
                }
            }
        }
        if (pending != (state.nextOccurrence == null ? 0 : 1)) {
            throw new CorruptStateException(
                    "Next occurrence is not linked to exactly one pending occurrence: " + id);
        }
        if (running > 1 || state.status == ScheduleRecord.Status.completed && running > 0) {
            throw new CorruptStateException("Invalid running occurrence count or completed schedule: " + id);
        }
    }

    static String name(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
                || value.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])")) {
            throw new IllegalArgumentException("Invalid schedule or occurrence identifier");
        }
        return value;
    }

}
