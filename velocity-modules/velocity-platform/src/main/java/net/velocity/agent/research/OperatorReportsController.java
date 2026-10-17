package net.velocity.agent.research;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import net.velocity.agent.AgentGateway;
import net.velocity.agent.delivery.DeliveryStateReader;
import net.velocity.agent.persistence.NotFoundException;
import net.velocity.agent.state.ExecutionSnapshot;

import jakarta.servlet.http.HttpServletRequest;

/** Protected administrative reads of persisted output remain available without inference. */
@RestController
public final class OperatorReportsController {

    private final AgentGateway gateway;
    private final ResearchService research;
    private final ObjectProvider<DeliveryStateReader> deliveryReaders;

    public OperatorReportsController(
            AgentGateway gateway,
            ResearchService research,
            ObjectProvider<DeliveryStateReader> deliveryReaders) {
        this.gateway = gateway;
        this.research = research;
        this.deliveryReaders = deliveryReaders;
    }

    @GetMapping("/api/operator/tasks/saved")
    List<ExecutionSnapshot> tasks(HttpServletRequest request) throws IOException {
        empty(request);
        return gateway.list();
    }

    @GetMapping("/api/operator/tasks/{id}/saved")
    ExecutionSnapshot task(@PathVariable String id, HttpServletRequest request) throws IOException {
        empty(request);
        return gateway.get(id);
    }

    public record ReportView(
            ResearchArtifact report,
            boolean staged,
            boolean currentGeneration,
            String deliveryStatus,
            String remoteFileId,
            String deliveryFailure) {}

    @GetMapping("/api/operator/tasks/{id}/reports")
    List<ReportView> reports(@PathVariable String id, HttpServletRequest request) throws IOException {
        empty(request);
        var snapshot = gateway.get(id);
        var runtime = deliveryReaders.getIfAvailable();
        var delivery = "slack".equals(snapshot.scope.origin()) && runtime != null
                ? runtime.deliveryState(id)
                : null;
        return research.adminList(id).stream().map(report -> {
            boolean staged = snapshot.outputReports.stream()
                    .anyMatch(value -> value.reportId().equals(report.id()));
            String status = "slack".equals(snapshot.scope.origin()) ? "not_staged" : "local_download";
            String remote = null;
            String failure = null;
            if (staged && "slack".equals(snapshot.scope.origin())) {
                status = runtime == null ? "slack_unavailable" : "pending";
            }
            if (staged && "slack".equals(snapshot.scope.origin()) && (snapshot.cancellationRequested
                    || snapshot.status.terminal()
                            && snapshot.status != net.velocity.agent.state.ExecutionStatus.succeeded)) {
                status = "suppressed";
            }
            if (delivery != null) {
                for (var turn : delivery.turns.values()) {
                    var upload = turn.uploads.get(report.id());
                    if (upload != null) {
                        remote = upload.fileId;
                        status = upload.completed
                                ? "delivered"
                                : upload.bytesAcknowledged ? "completion_pending" : "upload_pending";
                    }
                    if (turn.pending != null && turn.pending.file() != null
                            && report.id().equals(turn.pending.file().report().reportId())) {
                        status = turn.uncertain ? "uncertain" : "pending";
                        failure = turn.failure;
                    }
                }
            }
            return new ReportView(
                    report,
                    staged,
                    report.goalGeneration() == snapshot.goalGeneration,
                    status,
                    remote,
                    failure);
        }).toList();
    }

    @GetMapping("/api/operator/tasks/{id}/reports/{reportId}/download")
    ResponseEntity<byte[]> download(
            @PathVariable String id,
            @PathVariable String reportId,
            HttpServletRequest request) throws IOException {
        empty(request);
        gateway.get(id); // Administrative inspection intentionally does not impersonate the task owner.
        var report = research.adminList(id).stream().filter(value -> value.id().equals(reportId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Report not found"));
        byte[] content = research.adminDownload(id, reportId)
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Report content not found"));
        if (!report.filename().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Invalid stored report filename");
        }
        MediaType type = "text/markdown".equals(report.mediaType())
                || "text/markdown; charset=UTF-8".equals(report.mediaType())
                        ? MediaType.parseMediaType("text/markdown; charset=UTF-8")
                        : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok().header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(report.filename()).build().toString())
                .header("X-Content-Type-Options", "nosniff").header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(type).contentLength(content.length).body(content);
    }

    private static void empty(HttpServletRequest request) throws IOException {
        if (request.getQueryString() != null || request.getInputStream().read() != -1) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Saved report reads accept no body or query parameters");
        }
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Map<String, String>> missing() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Execution not found"));
    }
}
