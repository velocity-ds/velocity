package net.velocity.agent.learning;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.velocity.agent.state.InferenceBinding;
import net.velocity.agent.state.TrustedScope;

/** One bounded, atomically replaced owner journal: acknowledgement, cadence and review intent commit together. */
public final class LearningState {

    public int formatVersion = 1;
    public String ownerAccountId;
    public TrustedScope scope;
    public long revision;
    public long userTurns;
    public long toolIterations;
    public long reviewedUserTurns;
    public long reviewedToolIterations;
    /** Completed fact reviews since the derived profile was last refreshed. */
    public long factReviewsSinceProfile;
    /** When the curator last ran or was first armed; null until the first acknowledgement. */
    public String lastCuratorAt;
    public Map<String, Integer> acknowledgedTurns = new LinkedHashMap<>();
    public Map<String, Integer> acknowledgedIterations = new LinkedHashMap<>();
    public List<String> acknowledgementIds = new ArrayList<>();
    public List<Interaction> interactions = new ArrayList<>();
    public List<Review> reviews = new ArrayList<>();

    public record Interaction(
            String executionId,
            long generation,
            String digest,
            String text,
            InferenceBinding inferenceBinding) {}

    public static final class Review {

        public String id;
        public String mode;
        public String status = "queued";
        public String outcome;
        public String createdAt;
        public String completedAt;
        public long userWatermark;
        public long toolWatermark;
        public List<Interaction> inputs = new ArrayList<>();
        public Map<String, Long> policyGenerations = new LinkedHashMap<>();
        public List<Map<String, Object>> calls = new ArrayList<>();
        public long totalTokens;
        public int modelTurns;
        public int toolCalls;
        public boolean usageMissing;
        public boolean mutationInFlight;
        /** Bounded, non-secret cause of a failed review; null unless the review failed. */
        public String failureDetail;
        /** True when repeated failures advanced the cadence watermark past this review's inputs. */
        public boolean advancedWatermark;
        /** A review the user asked for explicitly; it ignores cadence and creates user-owned skills. */
        public boolean userRequested;
        /** Bounded user guidance for a requested review; null otherwise. */
        public String focus;
        /** Bounded, generic summaries of committed or staged mutations, for notifications and the journey. */
        public List<String> changes = new ArrayList<>();
        /** Delivery state of the learning notice: null (none), pending, sent, failed, uncertain or skipped. */
        public String notification;
    }
}
