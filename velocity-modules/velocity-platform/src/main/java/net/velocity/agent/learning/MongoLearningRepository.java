package net.velocity.agent.learning;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.fasterxml.jackson.databind.json.JsonMapper;

public final class MongoLearningRepository implements LearningRepository {

    public static final String COLLECTION = "agent_learning_owners";
    private static final JsonMapper JSON = new JsonMapper();
    private static final int MAX_BYTES = 12 * 1024 * 1024;
    private final MongoTemplate mongo;

    public MongoLearningRepository(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Optional<LearningState> find(String owner) {
        return Optional.ofNullable(mongo.getCollection(COLLECTION).find(new Document("_id", owner)).first())
                .map(document -> decode(document, owner));
    }

    @Override
    public List<LearningState> pending() {
        var values = new ArrayList<LearningState>();
        mongo.getCollection(COLLECTION)
                .find(
                        new Document(
                                "reviews.status",
                                new Document("$in", List.of("queued", "running", "uncertain"))))
                .limit(100).forEach(doc -> values.add(decode(doc)));
        return List.copyOf(values);
    }

    @Override
    public boolean save(LearningState value, long revision) {
        try {
            validate(value);
            if (value.revision != revision + 1) {
                throw new IllegalArgumentException("Learning revision must increment");
            }
            byte[] bytes = JSON.writeValueAsBytes(value);
            if (bytes.length > MAX_BYTES) {
                throw new IllegalStateException("Learning journal capacity exceeded");
            }
            Document doc = Document.parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            doc.put("_id", value.ownerAccountId);
            doc.put("owner_account_id", doc.remove("ownerAccountId"));
            if (!mongo.getCollection(COLLECTION).getWriteConcern().isAcknowledged()) {
                throw new IllegalStateException("Learning writes require acknowledgement");
            }
            if (revision == 0) {
                try {
                    return mongo.getCollection(COLLECTION).insertOne(doc).wasAcknowledged();
                } catch (com.mongodb.MongoWriteException conflict) {
                    if (conflict.getError().getCode() == 11000) {
                        return false;
                    }
                    throw conflict;
                }
            }
            var write = mongo.getCollection(COLLECTION)
                    .replaceOne(new Document("_id", value.ownerAccountId).append("revision", revision), doc);
            return write.wasAcknowledged() && write.getModifiedCount() == 1;
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Cannot encode learning journal", failure);
        }
    }

    private LearningState decode(Document document) {
        return decode(document, null);
    }

    private LearningState decode(Document document, String requestedOwner) {
        try {
            Document copy = new Document(document);
            copy.remove("_id");
            copy.put("ownerAccountId", copy.remove("owner_account_id"));
            String json = copy.toJson();
            if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES) {
                throw new IllegalStateException("Learning journal capacity exceeded");
            }
            LearningState state = JSON.readValue(json, LearningState.class);
            validate(state);
            if (!state.ownerAccountId.equals(document.get("_id"))
                    || requestedOwner != null && !requestedOwner.equals(state.ownerAccountId)) {
                throw new IllegalStateException("Learning journal ownership mismatch");
            }
            return state;
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Cannot decode learning journal", failure);
        }
    }

    static void validate(LearningState state) {
        try {
            if (state.formatVersion != 1 || state.scope == null
                    || !state.scope.requireOwnerAccountId().equals(state.ownerAccountId)
                    || state.revision < 1 || state.userTurns < 0 || state.toolIterations < 0
                    || state.factReviewsSinceProfile < 0
                    || state.reviewedUserTurns < 0 || state.reviewedUserTurns > state.userTurns
                    || state.reviewedToolIterations < 0 || state.reviewedToolIterations > state.toolIterations
                    || state.acknowledgementIds.size() > 5000 || state.reviews.size() > 50
                    || state.interactions.size() > 20
                    || state.acknowledgedTurns.size() > 5000 || state.acknowledgedIterations.size() > 5000
                    || new java.util.HashSet<>(state.acknowledgementIds).size() != state.acknowledgementIds
                            .size()) {
                invalid();
            }
            state.acknowledgementIds.forEach(MongoLearningRepository::hash);
            if (state.lastCuratorAt != null) {
                Instant.parse(state.lastCuratorAt);
            }
            long userTurns = 0, toolIterations = 0;
            for (var entry : state.acknowledgedTurns.entrySet()) {
                identifier(entry.getKey());
                if (entry.getValue() < 0) {
                    invalid();
                }
                userTurns = Math.addExact(userTurns, entry.getValue());
            }
            for (var entry : state.acknowledgedIterations.entrySet()) {
                identifier(entry.getKey());
                if (entry.getValue() < 0) {
                    invalid();
                }
                toolIterations = Math.addExact(toolIterations, entry.getValue());
            }
            if (userTurns != state.userTurns || toolIterations != state.toolIterations) {
                invalid();
            }
            for (var interaction : state.interactions) {
                interaction(interaction);
            }
            var reviewIds = new java.util.HashSet<String>();
            for (var review : state.reviews) {
                hash(review.id);
                if (!reviewIds.add(review.id)) {
                    invalid();
                }
                if (!Set.of("facts", "skills", "profile", "curator").contains(review.mode)
                        || !Set.of(
                                "queued",
                                "running",
                                "completed",
                                "cancelled",
                                "uncertain",
                                "interrupted",
                                "failed",
                                "limited",
                                "conflict",
                                "reconciled").contains(review.status)
                        || review.userWatermark < 0 || review.userWatermark > state.userTurns
                        || review.toolWatermark < 0 || review.toolWatermark > state.toolIterations
                        || review.totalTokens < 0 || review.totalTokens > 100_000_000 || review.modelTurns < 0
                        || review.modelTurns > 20
                        || review.toolCalls < 0 || review.toolCalls > 40 || review.inputs.size() > 20
                        || review.calls.size() != review.toolCalls || review.policyGenerations.size() > 4) {
                    invalid();
                }
                Instant.parse(review.createdAt);
                if (review.completedAt != null) {
                    Instant.parse(review.completedAt);
                }
                if (review.outcome != null && !review.outcome.matches("[a-z_]{1,100}")) {
                    invalid();
                }
                if (review.failureDetail != null
                        && (review.failureDetail.isBlank() || review.failureDetail.length() > 400)) {
                    invalid();
                }
                if (review.focus != null && (review.focus.isBlank() || review.focus.length() > 400)) {
                    invalid();
                }
                if (review.notification != null
                        && !Set.of("pending", "sent", "failed", "uncertain", "skipped")
                                .contains(review.notification)) {
                    invalid();
                }
                if (review.changes.size() > 8
                        || review.changes.stream()
                                .anyMatch(change -> change.isBlank() || change.length() > 200)) {
                    invalid();
                }
                int chars = 0;
                for (var source : review.inputs) {
                    interaction(source);
                    chars = Math.addExact(chars, source.text().length());
                }
                if (chars > 100000) {
                    invalid();
                }
                for (var generation : review.policyGenerations.entrySet()) {
                    if (!Set.of("user", "environment", "profile", "skills").contains(generation.getKey())
                            || generation.getValue() < 0) {
                        invalid();
                    }
                }
                var callIds = new java.util.HashSet<String>();
                for (var call : review.calls) {
                    if (!Set.of("call_id", "binding", "name", "target", "status")
                            .containsAll(call.keySet())) {
                        invalid();
                    }
                    String id = bounded(call.get("call_id"), 512);
                    if (!callIds.add(id)) {
                        invalid();
                    }
                    hash(bounded(call.get("binding"), 64));
                    if (!Set.of(
                            "memory_inspect",
                            "memory_update",
                            "skill_list",
                            "skill_load",
                            "skill_reference",
                            "skill_create",
                            "skill_patch",
                            "skill_curate")
                            .contains(bounded(call.get("name"), 64))) {
                        invalid();
                    }
                    if (!bounded(call.get("status"), 64).matches("[a-z_]{1,64}")) {
                        invalid();
                    }
                    if (call.containsKey("target")
                            && !Set.of("", "user", "environment", "profile").contains(call.get("target"))) {
                        invalid();
                    }
                }
                if (review.mutationInFlight && (review.calls.isEmpty()
                        || !"dispatched".equals(review.calls.getLast().get("status")))) {
                    invalid();
                }
            }
        } catch (RuntimeException corruption) {
            throw new IllegalStateException("Corrupt learning journal", corruption);
        }
    }

    private static void interaction(LearningState.Interaction source) {
        identifier(source.executionId());
        hash(source.digest());
        if (source.generation() < 1 || source.text() == null || source.text().length() > 100000
                || source.inferenceBinding() == null) {
            invalid();
        }
    }

    private static void identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")) {
            invalid();
        }
    }

    private static void hash(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}")) {
            invalid();
        }
    }

    private static String bounded(Object value, int max) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > max) {
            invalid();
            return "";
        }
        return (String) value;
    }

    private static void invalid() {
        throw new IllegalArgumentException("Invalid bounded learning state");
    }
}
