package net.velocity.agent.slack;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import net.velocity.agent.state.ExecutionSnapshot;

/** A control is bound to the entire current pause, revision, turn, destination, and initiating user. */
public final class SlackControlBinding {

    private SlackControlBinding() {
    }

    public static String value(ExecutionSnapshot snapshot, String action) {
        String binding = new com.google.gson.Gson().toJson(
                java.util.List.of(
                        snapshot.executionId,
                        snapshot.deliveryTurn,
                        snapshot.revision,
                        snapshot.scope,
                        snapshot.sourceTurn,
                        snapshot.pendingQuestions,
                        action));
        return snapshot.executionId + ":" + snapshot.revision + ":" + digest(binding);
    }

    public static String digest(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
