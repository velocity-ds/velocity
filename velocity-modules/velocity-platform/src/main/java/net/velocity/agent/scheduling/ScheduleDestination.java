package net.velocity.agent.scheduling;

/** A saved delivery address, separate from the trusted identity of the scheduled execution. */
public record ScheduleDestination(String transport, String channelId, String threadId) {

    public ScheduleDestination {
        if (transport == null || transport.isBlank()) {
            throw new IllegalArgumentException("Destination transport is required");
        }
        if (channelId == null || channelId.isBlank()) {
            throw new IllegalArgumentException("Destination channel is required");
        }
        if (threadId != null && threadId.isBlank()) {
            throw new IllegalArgumentException("Destination thread must be omitted or nonblank");
        }
    }
}
