package net.velocity.agent.state;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** An internal conversation whose identity, resources and lifetime belong to its root execution. */
public final class ChildRun extends ConversationState {

    public String id;
    public String rootExecutionId;
    public String parentCallId;
    public String parentCallBinding;
    /** Native conversation pin; required, and a child without its own binding is corrupt state. */
    public InferenceBinding inferenceBinding;
    public long goalGeneration;
    public String profile;
    public int profileVersion = 1;
    public String objective;
    public List<Map<String, Object>> references = new ArrayList<>();
    public String context;
    public Instant createdAt;
    public Instant updatedAt;
    public String result;
    public boolean modelRequestInFlight;
    public String activeUsageSource;
    public long reservedTokens;
    public int providerRequests;
    public Map<String, Long> requestReservations = new LinkedHashMap<>();
    public int repairAttempts;
    public int resultBytes = 8192;
    public int maxStateBytes = 256 * 1024;
    public Map<String, Settlement> receipts = new LinkedHashMap<>();

    public ChildRun() {
        limits = null;
    }

    /** Length-prefixing avoids ambiguous concatenations while retaining stable recovery identity. */
    public static String id(String rootId, String wrapperId, String binding, long generation) {
        if (generation < 1) {
            throw new IllegalArgumentException("Child generation must be positive");
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String value : List.of(
                    Objects.requireNonNull(rootId),
                    Objects.requireNonNull(wrapperId),
                    Objects.requireNonNull(binding),
                    Long.toString(generation))) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':');
                digest.update(bytes);
            }
            return "child-" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
