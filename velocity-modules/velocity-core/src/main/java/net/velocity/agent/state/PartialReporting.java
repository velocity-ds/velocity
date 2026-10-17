package net.velocity.agent.state;

/**
 * Immutable reason and expected model intent for the bounded partial-answer request. The mode exists
 * only while a snapshot holds this record, so an attempted request without a mode cannot be represented.
 * {@code attempted} is saved together with the provider request intent; an uncertain reporting attempt is
 * never retried. A reporting response that was received and committed but is unusable (it proposed tool
 * calls while tools were disabled) permits exactly one further request, marked by the snapshot's
 * {@code unusableResponses} counter, which the mode resets on entry and holds at one for the retry.
 */
public record PartialReporting(String reason, int startedModelTurn, boolean attempted) {

    public PartialReporting {
        if (reason == null || !reason.matches("[a-z][a-z0-9_]{0,95}") || startedModelTurn < 1) {
            throw new IllegalArgumentException("Invalid partial reporting mode");
        }
    }

    /** The mode saved before its sole provider request is recorded. */
    public static PartialReporting pending(String reason, int startedModelTurn) {
        return new PartialReporting(reason, startedModelTurn, false);
    }

    /** The same mode once its provider request intent is saved. */
    public PartialReporting markAttempted() {
        return new PartialReporting(reason, startedModelTurn, true);
    }

    /**
     * Whether the attempted reporting response was committed and rejected as unusable, so one further
     * reporting request may start. The counter is set only after the response is saved, so a crash
     * during the first request can never satisfy this.
     */
    public boolean retryAllowed(int modelTurns, int unusableResponses) {
        return attempted && unusableResponses == 1 && modelTurns == startedModelTurn;
    }

    /** Whether the single permitted retry request has been started. */
    public boolean retried(int modelTurns, int unusableResponses) {
        return attempted && unusableResponses == 1 && modelTurns == startedModelTurn + 1;
    }
}
