package net.velocity.agent.state;

/** Domain result is durable before the runner writes its tool settlement. */
public record ReportStagingReceipt(String callId, String binding, String reportId, long goalGeneration) {}
