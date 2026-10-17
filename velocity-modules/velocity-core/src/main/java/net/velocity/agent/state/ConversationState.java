package net.velocity.agent.state;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.velocity.inference.objects.InferenceMessage;

/** Mutable conversation fields shared by a root task and its internal child runs. */
public class ConversationState {

    public String finalAnswer;
    public String failure;
    public ExecutionStatus status = ExecutionStatus.running;
    public List<InferenceMessage> messages = new ArrayList<>();
    public List<AcceptedCall> calls = new ArrayList<>();
    public List<String> blockers = new ArrayList<>();
    public ExecutionLimits limits = ExecutionLimits.defaults();
    public long totalTokens;
    public long inputTokens;
    public long outputTokens;
    public boolean usageMissing;
    public Map<String, UsageCheckpoint> usageSources = new LinkedHashMap<>();
    public long activeMillis;
    public int modelTurns;
    public int toolCalls;
    public int unusableResponses;

    /** An ephemeral view only; repository reads and writes perform the detached deep copy. */
    public void copyTo(ConversationState target) {
        target.finalAnswer = finalAnswer;
        target.failure = failure;
        target.status = status;
        target.messages = messages;
        target.calls = calls;
        target.blockers = blockers;
        target.limits = limits;
        target.totalTokens = totalTokens;
        target.inputTokens = inputTokens;
        target.outputTokens = outputTokens;
        target.usageMissing = usageMissing;
        target.usageSources = usageSources;
        target.activeMillis = activeMillis;
        target.modelTurns = modelTurns;
        target.toolCalls = toolCalls;
        target.unusableResponses = unusableResponses;
    }
}
