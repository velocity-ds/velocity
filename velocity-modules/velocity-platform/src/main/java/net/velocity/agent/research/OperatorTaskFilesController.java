package net.velocity.agent.research;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import net.velocity.agent.AgentGateway;
import net.velocity.agent.persistence.NotFoundException;

import jakarta.servlet.http.HttpServletRequest;

/** Administrative reads of the files a requester supplied with a task; available without inference. */
@RestController
public final class OperatorTaskFilesController {

    private final AgentGateway gateway;
    private final ResearchService research;

    public OperatorTaskFilesController(AgentGateway gateway, ResearchService research) {
        this.gateway = gateway;
        this.research = research;
    }

    @GetMapping("/api/operator/tasks/{id}/files")
    List<ResearchService.FileView> files(@PathVariable String id, HttpServletRequest request)
            throws IOException {
        empty(request);
        gateway.get(id);
        return research.adminListFiles(id);
    }

    @GetMapping("/api/operator/tasks/{id}/files/{fileId}/download")
    ResponseEntity<byte[]> download(
            @PathVariable String id,
            @PathVariable String fileId,
            HttpServletRequest request) throws IOException {
        empty(request);
        gateway.get(id); // Administrative inspection intentionally does not impersonate the task owner.
        var file = research.adminListFiles(id).stream().map(ResearchService.FileView::file)
                .filter(value -> value.id().equals(fileId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found"));
        byte[] content = research.adminDownloadFile(id, fileId)
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "File content not found"));
        if (!file.filename().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Invalid stored filename");
        }
        // Downloads never render inline: every stored type is served as an opaque attachment.
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.filename()).build().toString())
                .header("X-Content-Type-Options", "nosniff").header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(content.length).body(content);
    }

    private static void empty(HttpServletRequest request) throws IOException {
        if (request.getQueryString() != null || request.getInputStream().read() != -1) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Task file reads accept no body or query parameters");
        }
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Map<String, String>> missing() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Execution not found"));
    }
}
