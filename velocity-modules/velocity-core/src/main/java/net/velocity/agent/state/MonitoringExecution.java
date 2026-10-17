package net.velocity.agent.state;

/** Trusted persisted link from a bounded execution to its monitoring definition and occurrence. */
public record MonitoringExecution(
        String jobId,
        String scheduleId,
        String occurrenceId,
        long definitionRevision) {

    public MonitoringExecution {
        for (String id : new String[]{jobId, scheduleId, occurrenceId}) {
            if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")) {
                throw new IllegalArgumentException("Invalid monitoring execution association");
            }
        }
        if (definitionRevision < 1) {
            throw new IllegalArgumentException("Monitoring definition revision must be positive");
        }
    }
}
