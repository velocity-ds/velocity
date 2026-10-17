package net.velocity.agent.state;

/** Durable cumulative high-water marks for one model invocation or delegated usage source. */
public final class UsageCheckpoint {

    public long inputTokens;
    public long outputTokens;
    public long totalTokens;
    public boolean missing = true;
}
