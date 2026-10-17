package net.velocity.agent.monitoring;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/** Immutable evidence version. Selection and delivery belong to separate notification records. */
public final class MonitoringObservation {

    public int formatVersion = 2;
    public String id;
    public String ownerAccountId;
    public String jobId;
    public String runId;
    public String sourceId;
    public String itemId;
    public String fingerprint;
    public Instant observedAt;
    public Instant sourceTime;
    public Map<String, JsonNode> facts = new LinkedHashMap<>();
    public List<String> evidence = new ArrayList<>();
    public String priorObservationId;
    public String relevance;
    public long criteriaVersion;
    public boolean matched;
}
