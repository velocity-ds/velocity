package net.velocity.agent.monitoring;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/** Findings and concise conclusions only. No model-supplied identity, lifecycle or receipt fields. */
public record MonitoringResult(
        Outcome outcome,
        String summary,
        List<Observation> observations,
        List<CandidateUpdate> updates,
        List<Coverage> coverage,
        String freshness,
        Map<String, String> checkpoints,
        List<String> questions,
        List<String> limitations,
        @JsonProperty("completion_evidence") List<String> completionEvidence) {

    public enum Outcome {
        success, partial, needs_input, failed
    }

    public enum Change {
        new_match, material_change, condition
    }

    public record Observation(
            String key,
            @JsonProperty("source_id") String sourceId,
            @JsonProperty("item_id") String itemId,
            List<String> evidence,
            @JsonProperty("source_time") Instant sourceTime,
            Map<String, JsonNode> facts,
            @JsonProperty("prior_observation_id") String priorObservationId,
            String relevance) {}

    public record CandidateUpdate(
            @JsonProperty("observation_keys") List<String> observationKeys,
            Change change,
            String relevance,
            String text) {}

    public record Coverage(@JsonProperty("source_id") String sourceId, boolean complete, String detail) {}
}
