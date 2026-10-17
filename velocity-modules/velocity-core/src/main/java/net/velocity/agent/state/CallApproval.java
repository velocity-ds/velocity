package net.velocity.agent.state;

import java.time.Instant;

/**
 * The requester's decision about one accepted call that the approval policy held back. Absent on calls that
 * needed no approval; {@code pending} keeps the execution in {@code waiting_for_user} until a trusted
 * adapter records the decision. Optional and additive: calls saved before the field existed decode with
 * null, so the snapshot format is unchanged; the same holds for {@code summary}.
 */
public record CallApproval(
        State state,
        Instant requestedAt,
        Instant decidedAt,
        String decidedVia,
        String summary) {

    /** Bound on the trusted plain-language description of a held call. */
    public static final int MAX_SUMMARY_CHARS = 2_000;

    public enum State {
        pending, approved, declined
    }

    public CallApproval {
        if (summary != null && summary.length() > MAX_SUMMARY_CHARS) {
            summary = summary.substring(0, MAX_SUMMARY_CHARS - 1) + "…";
        }
    }

    public static CallApproval pending(Instant now) {
        return pending(now, null);
    }

    /**
     * @param summary what the call will do, rendered by the tool's trusted registration from the actual
     *        arguments; null when the tool offers none, in which case adapters show the raw arguments
     */
    public static CallApproval pending(Instant now, String summary) {
        return new CallApproval(State.pending, now, null, null, summary);
    }

    public CallApproval decided(boolean approved, Instant now, String via) {
        return new CallApproval(approved ? State.approved : State.declined, requestedAt, now, via, summary);
    }

    public boolean pending() {
        return state == State.pending;
    }
}
