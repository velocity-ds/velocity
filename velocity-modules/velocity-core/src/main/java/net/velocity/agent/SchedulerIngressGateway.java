package net.velocity.agent;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import net.velocity.agent.state.*;

/** Entry point for application-owned JobRunr jobs. Schedule creation/ownership is a domain concern. */
public final class SchedulerIngressGateway {

    private final AgentGateway gateway;
    private final ContextHooks hooks;

    public SchedulerIngressGateway(AgentGateway gateway, ContextHooks hooks) {
        this.gateway = gateway;
        this.hooks = hooks;
    }

    /** Scope must come from the stored authorized schedule, never from generated arguments. */
    public ExecutionSnapshot execute(
            String scheduleId,
            String occurrenceId,
            String request,
            TrustedScope scheduleScope) {
        scheduleScope.requireOwnerAccountId();
        if (scheduleId == null || scheduleId.isBlank() || occurrenceId == null || occurrenceId.isBlank()
                || !"scheduler".equals(scheduleScope.origin())) {
            throw new IllegalArgumentException("Trusted schedule identity required");
        }
        var turn = new SourceTurn(
                scheduleScope.workspaceId(),
                scheduleScope.channelId(),
                scheduleId,
                occurrenceId);
        return gateway.createScheduled(
                executionId(scheduleId, occurrenceId),
                new StartRequest(
                        request,
                        scheduleId,
                        scheduleScope,
                        hooks.instructions(scheduleScope),
                        hooks.history(scheduleScope, turn),
                        List.of(),
                        turn,
                        null,
                        null));
    }

    /** Persist the server-owned monitoring identity; use only the job's bounded durable context. */
    public ExecutionSnapshot executeMonitoring(
            String scheduleId,
            String occurrenceId,
            String jobId,
            long definitionRevision,
            String request,
            String instructions,
            TrustedScope scheduleScope,
            ExecutionLimits limits) {
        scheduleScope.requireOwnerAccountId();
        if (!"scheduler".equals(scheduleScope.origin())) {
            throw new IllegalArgumentException("Trusted scheduler scope required");
        }
        var association = new MonitoringExecution(jobId, scheduleId, occurrenceId, definitionRevision);
        var turn = new SourceTurn(
                scheduleScope.workspaceId(),
                scheduleScope.channelId(),
                scheduleId,
                occurrenceId);
        String trustedInstructions = hooks.instructions(scheduleScope);
        return gateway.createScheduled(
                executionId(scheduleId, occurrenceId),
                new StartRequest(
                        request,
                        scheduleId,
                        scheduleScope,
                        (trustedInstructions == null ? "" : trustedInstructions + "\n")
                                + (instructions == null ? "" : instructions),
                        List.of(),
                        List.of(),
                        turn,
                        limits,
                        null,
                        List.of(),
                        association));
    }

    public static String executionId(String scheduleId, String occurrenceId) {
        return UUID.nameUUIDFromBytes(
                ("schedule:" + scheduleId + ":" + occurrenceId)
                        .getBytes(StandardCharsets.UTF_8))
                .toString();
    }
}
