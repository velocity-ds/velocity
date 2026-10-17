package net.velocity.agent.state;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Durable original model call; mutable only inside repository updates. */
public final class AcceptedCall {

    public String id;
    public String name;
    public String toolset;
    public String binding;
    /** Generation of the model context which produced this call. */
    public long goalGeneration;
    /** Provider response batch, distinct from individual dispatch count. */
    public int modelTurn;
    public String inputError;
    public Map<String, Object> input = new LinkedHashMap<>();
    public CallState state = CallState.accepted;
    public Settlement settlement;
    public Instant dispatchedAt;
    /** Operational text supplied by the executing handler, never model reasoning. */
    public String progress;
    /** The requester's approval decision when the approval policy held this call; null otherwise. */
    public CallApproval approval;

    public boolean awaitingApproval() {
        return state == CallState.accepted && approval != null && approval.pending();
    }
}
