package net.velocity.agent.research;

import java.util.List;
import java.util.Map;

import net.velocity.agent.ToolRegistry;
import net.velocity.agent.persistence.ConflictException;
import net.velocity.agent.persistence.ExecutionRepository;
import net.velocity.agent.state.ExecutionStatus;
import net.velocity.agent.state.OutputReportReference;
import net.velocity.agent.state.ReportStagingReceipt;
import net.velocity.inference.SystemPrompts;

/**
 * Stages a managed report for the trusted task destination; this tool performs no external delivery. The
 * {@link #stage} step is shared with {@code task_attach_file}, so one staging receipt rule covers reports and
 * output files alike.
 */
public final class ReportTools {

    private ReportTools() {
    }

    public static ToolRegistry register(
            ToolRegistry registry,
            ExecutionRepository executions,
            ResearchService research) {
        return registry.register(
                new ToolRegistry.Definition(
                        "task_attach_report",
                        SystemPrompts.TASK_ATTACH_REPORT_DESCRIPTION,
                        Map.of(
                                "type",
                                "object",
                                "properties",
                                Map.of(
                                        "report_id",
                                        Map.of(
                                                "type",
                                                "string",
                                                "minLength",
                                                1,
                                                "maxLength",
                                                128)),
                                "required",
                                List.of("report_id"),
                                "additionalProperties",
                                false),
                        Map.of(
                                "type",
                                "object",
                                "properties",
                                Map.of(
                                        "status",
                                        Map.of(
                                                "type",
                                                "string",
                                                "enum",
                                                List.of("staged", "not_found", "superseded")),
                                        "report_id",
                                        Map.of("type", "string", "maxLength", 128),
                                        "goal_generation",
                                        Map.of("type", "integer", "minimum", 1),
                                        "current_selection",
                                        Map.of("type", "boolean")),
                                "required",
                                List.of("status", "report_id"),
                                "additionalProperties",
                                false),
                        "repository.write",
                        true,
                        true,
                        false,
                        10_000,
                        ToolRegistry.MonitoringAccess.denied,
                        (context, input) -> {
                            String id = (String) input.get("report_id");
                            try {
                                return stage(executions, research, context, id);
                            } catch (ConflictException superseded) {
                                return Map.of("status", "superseded", "report_id", id);
                            }
                        }));
    }

    static Object stage(
            ExecutionRepository executions,
            ResearchService research,
            ToolRegistry.Context context,
            String id) {
        if (context.childRunId() != null) {
            throw new IllegalArgumentException("Reports may only be staged by the parent task");
        }
        if (context.acceptedCallId().isBlank()) {
            throw new IllegalArgumentException("Accepted call provenance required");
        }
        var found = research.ownedDeliverable(context.executionId(), context.scope(), id);
        if (found.isEmpty()) {
            return Map.of("status", "not_found", "report_id", id);
        }
        var report = found.get();
        var saved = executions.update(context.executionId(), -1, snapshot -> {
            if (!snapshot.scope.equals(context.scope())) {
                throw new SecurityException("Output owner mismatch");
            }
            var prior = snapshot.reportStagingReceipts.get(context.acceptedCallId());
            if (prior != null) {
                if (!prior.binding().equals(context.acceptedCallBinding()) || !prior.reportId().equals(id)) {
                    throw new ConflictException("Report staging receipt mismatch");
                }
                return; // Domain replay preserves its result and never restages an obsolete generation.
            }
            var call = snapshot.calls.stream().filter(
                    value -> value.id.equals(context.acceptedCallId())
                            && context.acceptedCallBinding().equals(value.binding))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown accepted call"));
            if (context.cancellation().cancelled() || snapshot.cancellationRequested
                    || snapshot.status != ExecutionStatus.running
                    || call.goalGeneration != snapshot.goalGeneration
                    || report.goalGeneration() != snapshot.goalGeneration
                    || executions.inbox(snapshot.executionId).stream()
                            .anyMatch(c -> !snapshot.consumedInboxIds.contains(c.id()))) {
                throw new ConflictException("Report belongs to a superseded or inactive goal");
            }
            if (snapshot.outputReports.stream().noneMatch(value -> id.equals(value.reportId()))) {
                if (snapshot.outputReports.size() >= 10) {
                    throw new IllegalArgumentException("At most ten reports and files may be staged");
                }
                snapshot.outputReports.add(
                        new OutputReportReference(
                                id,
                                report.goalGeneration(),
                                report.filename(),
                                report.mediaType(),
                                report.byteSize(),
                                report.checksum(),
                                report.coverage()));
            }
            snapshot.reportStagingReceipts.put(
                    context.acceptedCallId(),
                    new ReportStagingReceipt(
                            context.acceptedCallId(),
                            context.acceptedCallBinding(),
                            id,
                            report.goalGeneration()));
        });
        var receipt = saved.reportStagingReceipts.get(context.acceptedCallId());
        return Map.of(
                "status",
                "staged",
                "report_id",
                receipt.reportId(),
                "goal_generation",
                receipt.goalGeneration(),
                "current_selection",
                saved.outputReports.stream().anyMatch(value -> value.reportId().equals(id)));
    }
}
