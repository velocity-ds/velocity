package net.velocity.agent.research;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.json.JsonMapper;

import net.velocity.ApplicationTime;
import net.velocity.agent.AttachmentStore;
import net.velocity.agent.CallProvenance;
import net.velocity.agent.ToolRegistry;
import net.velocity.agent.config.UploadProperties;
import net.velocity.agent.delegation.ChildReferenceValidator;
import net.velocity.agent.persistence.ExecutionRepository;
import net.velocity.agent.persistence.NotFoundException;
import net.velocity.agent.spi.OutputFileSource;
import net.velocity.agent.state.ExecutionSnapshot;
import net.velocity.agent.state.TrustedScope;
import net.velocity.agent.state.UploadedFile;
import net.velocity.util.Sha256;

/**
 * Trusted execution-owned evidence, immutable documents, requester-supplied files and deterministic report
 * rendering.
 */
public final class ResearchService implements AttachmentStore, ChildReferenceValidator, AutoCloseable {

    /** Artifact kind of a file the requester supplied with the task; its contents are never read at upload. */
    public static final String UPLOAD_KIND = "upload";

    /** Artifact kind of a file the task produced for the requester: a screenshot, an image or a written file. */
    public static final String OUTPUT_KIND = "output";

    /** Artifact kinds that {@code task_attach_report} and {@code task_attach_file} may stage for delivery. */
    public static final java.util.Set<String> DELIVERABLE_KINDS = java.util.Set.of("report", OUTPUT_KIND);

    /** Owned artifact kinds whose stored bytes may be copied into a new output file. */
    private static final java.util.Set<String> COPYABLE_KINDS = java.util.Set
            .of(UPLOAD_KIND, "document", "evidence", "report", OUTPUT_KIND);

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ResearchRepository repository;
    private final ExecutionRepository executions;
    private final ResearchProperties properties;
    private final UploadProperties uploads;
    private final ControlledDownloader downloader;
    private final FileInferenceExtractor fileExtractor;
    private final Clock clock;
    private final ExecutorService parsers = Executors.newVirtualThreadPerTaskExecutor();

    public ResearchService(
            ResearchRepository repository,
            ExecutionRepository executions,
            ResearchProperties properties,
            UploadProperties uploads,
            ControlledDownloader downloader,
            Clock clock,
            FileInferenceExtractor fileExtractor) {
        this.repository = Objects.requireNonNull(repository);
        this.executions = Objects.requireNonNull(executions);
        this.properties = Objects.requireNonNull(properties);
        this.uploads = Objects.requireNonNull(uploads);
        this.downloader = Objects.requireNonNull(downloader);
        this.clock = Objects.requireNonNull(clock);
        this.fileExtractor = Objects.requireNonNull(fileExtractor);
    }

    @Override
    public List<Stored> store(String executionId, TrustedScope scope, List<UploadedFile> files) {
        UploadTypes.validate(files, uploads);
        var stored = new ArrayList<Stored>();
        try {
            int index = 0;
            for (UploadedFile file : files) {
                stored.add(storeOne(executionId, scope, file, Integer.toString(index)));
                index++;
            }
        } catch (RuntimeException failure) {
            // A later file failing must not leave earlier ones behind: the caller never learns their IDs.
            if (!stored.isEmpty()) {
                repository.deleteExecution(executionId);
            }
            throw failure;
        }
        return List.copyOf(stored);
    }

    @Override
    public List<Stored> attach(String executionId, TrustedScope scope, List<UploadedFile> files) {
        if (files.isEmpty()) {
            return List.of();
        }
        UploadTypes.validate(files, uploads);
        List<ResearchArtifact> existing = ownedUploads(executionId, scope);
        if (existing.size() + files.size() > uploads.maxFiles()) {
            throw new IllegalArgumentException(
                    "A task can carry at most " + uploads.maxFiles()
                            + " attached files; this one already has "
                            + existing.size());
        }
        long total = existing.stream().mapToLong(ResearchArtifact::byteSize).sum()
                + files.stream().mapToLong(file -> file.bytes().length).sum();
        if (total > uploads.maxTotalBytes()) {
            throw new IllegalArgumentException(
                    "The task's attached files would exceed the total limit of " + uploads.maxTotalBytes()
                            + " bytes");
        }
        // A later turn salts its IDs so a re-sent file never merges with an earlier upload of the same bytes.
        String turn = "turn\0" + existing.size() + "\0" + clock.instant();
        var stored = new ArrayList<Stored>();
        int index = 0;
        for (UploadedFile file : files) {
            // The repository cannot delete one artifact, so a failure here leaves earlier files on the task.
            stored.add(storeOne(executionId, scope, file, turn + "\0" + index));
            index++;
        }
        return List.copyOf(stored);
    }

    private Stored storeOne(String executionId, TrustedScope scope, UploadedFile file, String salt) {
        byte[] bytes = file.bytes();
        String checksum = hash(bytes);
        String id = "u_" + hash(
                (executionId + "\0" + salt + "\0" + checksum).getBytes(StandardCharsets.UTF_8));
        var artifact = new ResearchArtifact(
                id,
                UPLOAD_KIND,
                executionId,
                scope,
                1,
                file.filename(),
                UploadTypes.mediaType(file.filename(), bytes),
                bytes.length,
                checksum,
                clock.instant(),
                List.of(),
                "Supplied by the requester; contents have not been read",
                "",
                file.filename(),
                "uploaded",
                "none");
        Map<String, String> privateData = new LinkedHashMap<>();
        privateData.put("source", "requester_upload");
        privateData.put("declared_media_type", bounded(file.declaredMediaType(), 150));
        privateData.put("uploaded_at", clock.instant().toString());
        var saved = repository.save(artifact, bytes, privateData, properties.maxArtifacts());
        return new Stored(saved.id(), saved.filename(), saved.mediaType(), saved.byteSize());
    }

    @Override
    public void discard(String executionId) {
        repository.deleteExecution(executionId);
    }

    @Override
    public List<UploadedFile> stored(String executionId, TrustedScope scope) {
        return ownedUploads(executionId, scope).stream()
                .map(file -> new UploadedFile(file.filename(), file.mediaType(), repository.bytes(file.id())))
                .toList();
    }

    public Map<String, Object> listFiles(ToolRegistry.Context context, Map<String, Object> input) {
        execution(context);
        List<Map<String, Object>> files = new ArrayList<>();
        for (ResearchArtifact file : ownedUploads(context.executionId(), context.scope())) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("file_id", file.id());
            row.put("filename", file.filename());
            row.put("media_type", file.mediaType());
            row.put("byte_size", file.byteSize());
            ResearchArtifact document = extractedDocument(context.executionId(), context.scope(), file.id());
            if (document != null) {
                row.put("document_id", document.id());
                row.put("extraction_status", document.status());
            }
            files.add(row);
        }
        return Map.of("status", "ok", "files", files, "content_trust", "untrusted_data");
    }

    /** Extracts a requester-supplied file into an immutable document once; later calls return that document. */
    public Map<String, Object> openFile(ToolRegistry.Context context, Map<String, Object> input)
            throws Exception {
        ExecutionSnapshot snapshot = execution(context);
        String fileId = text(input, "file_id", 80);
        ResearchArtifact file = owned(context, fileId).filter(value -> value.kind().equals(UPLOAD_KIND))
                .orElse(null);
        if (file == null) {
            return status("not_found");
        }
        String receiptId = receiptId(context, "document");
        var prior = owned(context, receiptId);
        if (prior.isPresent()) {
            return Map.of(
                    "status",
                    "found",
                    "document",
                    metadata(prior.get()),
                    "cache_status",
                    "domain_receipt");
        }
        ResearchArtifact existing = extractedDocument(context.executionId(), context.scope(), fileId);
        if (existing != null) {
            return Map.of(
                    "status",
                    "found",
                    "document",
                    metadata(existing),
                    "cache_status",
                    "existing_document");
        }
        byte[] bytes = repository.bytes(fileId);
        String retrievedAt = clock.instant().toString();
        DocumentExtractor.Extraction extracted;
        try {
            extracted = context.cancellation().await(
                    parsers,
                    () -> fileExtractor.extract(
                            bytes,
                            file.mediaType(),
                            file.filename(),
                            context,
                            () -> beforeFileInference(context)),
                    fileExtractor.timeoutMillis() + 1000);
        } catch (java.util.concurrent.TimeoutException timeout) {
            throw new ResearchException(
                    "unavailable",
                    "Document reading deadline reached; no extracted evidence published");
        }
        context.cancellation().check();
        execution(context);
        ResearchArtifact artifact = new ResearchArtifact(
                receiptId,
                "document",
                context.executionId(),
                context.scope(),
                snapshot.goalGeneration,
                file.filename(),
                file.mediaType(),
                bytes.length,
                file.checksum(),
                clock.instant(),
                List.of(file.id()),
                extracted.coverage(),
                "",
                file.title(),
                extracted.status(),
                extracted.method());
        Map<String, String> privateData = provenance(
                context,
                Map.of(
                        "extraction",
                        JSON.writeValueAsString(extracted),
                        "retrieved_at",
                        retrievedAt,
                        "source",
                        "requester_upload"));
        ResearchArtifact saved = repository.save(artifact, bytes, privateData, properties.maxArtifacts());
        return Map.of("status", "found", "document", metadata(saved), "cache_status", "extracted");
    }

    private List<ResearchArtifact> ownedUploads(String executionId, TrustedScope scope) {
        return repository.list(executionId, UPLOAD_KIND, properties.maxArtifacts()).stream()
                .filter(item -> item.scope().sameOwner(scope))
                .sorted(
                        java.util.Comparator.comparing(ResearchArtifact::createdAt)
                                .thenComparing(ResearchArtifact::filename))
                .toList();
    }

    private ResearchArtifact extractedDocument(String executionId, TrustedScope scope, String fileId) {
        return repository.list(executionId, "document", properties.maxArtifacts()).stream()
                .filter(item -> item.scope().sameOwner(scope) && item.evidenceIds().contains(fileId))
                .findFirst().orElse(null);
    }

    private void beforeFileInference(ToolRegistry.Context context) {
        ExecutionSnapshot snapshot = currentGoal(context);
        if (!snapshot.usesTaskDeadlineOnly() && snapshot.totalTokens >= snapshot.limits.totalTokens()) {
            throw new ResearchException(
                    "limit_reached",
                    "Task token budget prevents another file inference request");
        }
    }

    public Map<String, Object> readDocument(ToolRegistry.Context context, Map<String, Object> input)
            throws Exception {
        execution(context);
        ResearchArtifact document = owned(context, text(input, "document_id", 80))
                .filter(value -> List.of("document", "evidence").contains(value.kind())).orElse(null);
        if (document == null) {
            return status("not_found");
        }
        DocumentExtractor.Extraction extraction = extraction(document);
        int index = number(input, "unit", 1, 1, Integer.MAX_VALUE) - 1;
        int offset = number(input, "offset", 0, 0, properties.maxExtractedChars());
        int limit = number(input, "limit", 4000, 1, 12000);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", extraction.status());
        result.put("document", metadata(document));
        result.put("extraction_method", extraction.method());
        result.put("content_trust", "untrusted_data");
        result.put("retrieved_at", retrievedAt(document));
        result.put("coverage", extraction.coverage());
        result.put("total_units", extraction.totalUnits());
        result.put("available_units", extraction.parts().size());
        result.put("unit", index + 1);
        result.put("offset", offset);
        if (index >= extraction.parts().size()) {
            result.put("text", "");
            result.put("complete", true);
            result.put("next_unit", -1);
            result.put("next_offset", -1);
            return result;
        }
        DocumentExtractor.Part part = extraction.parts().get(index);
        if (offset > part.text().length()) {
            throw invalid("Offset exceeds immutable unit length");
        }
        int end = offset + Math.min(limit, part.text().length() - offset);
        result.put("reference", part.reference());
        result.put("text", part.text().substring(offset, end));
        boolean endUnit = end == part.text().length(),
                complete = endUnit && index + 1 == extraction.parts().size();
        result.put("complete", complete);
        result.put("next_unit", complete ? -1 : endUnit ? index + 2 : index + 1);
        result.put("next_offset", complete ? -1 : endUnit ? 0 : end);
        return result;
    }

    public Map<String, Object> createReport(ToolRegistry.Context context, Map<String, Object> input)
            throws Exception {
        if (context.childRunId() != null) {
            throw invalid("Reports may only be created by the parent task");
        }
        ExecutionSnapshot start = currentGoal(context);
        String id = receiptId(context, "report");
        Optional<ResearchArtifact> replay = ownedDeliverable(context.executionId(), context.scope(), id)
                .filter(item -> item.kind().equals("report"));
        if (replay.isPresent()) {
            if (replay.get().goalGeneration() != start.goalGeneration) {
                throw new ResearchException("stale_goal", "Report belongs to a superseded task goal");
            }
            return Map.of("status", "created", "report", metadata(replay.get()), "receipt_replayed", true);
        }
        String title = text(input, "title", 200), summary = text(input, "summary", 12000),
                coverage = text(input, "coverage", 4000);
        List<Map<String, Object>> sections = objectList(input, "sections", 30);
        LinkedHashMap<String, ResearchArtifact> evidence = new LinkedHashMap<>();
        for (Map<String, Object> section : sections) {
            for (Map<String, Object> citation : objectList(section, "citations", 50)) {
                String evidenceId = text(citation, "evidence_id", 80);
                ResearchArtifact item = owned(context, evidenceId)
                        .filter(value -> List.of("document", "evidence").contains(value.kind()))
                        .orElseThrow(() -> invalid("A citation does not resolve to owned durable evidence"));
                String reference = optionalText(citation, "reference", 80);
                if (!reference.isEmpty() && extraction(item).parts().stream()
                        .noneMatch(part -> part.reference().equals(reference))) {
                    throw invalid("Citation page/chunk reference does not exist in immutable extraction");
                }
                evidence.put(evidenceId, item);
            }
        }
        StringBuilder markdown = new StringBuilder("# ").append(escape(title)).append("\n\n")
                .append(escape(summary)).append("\n\n");
        markdown.append("Created: ").append(clock.instant()).append("\n\nGoal generation: ")
                .append(start.goalGeneration).append("\n\n");
        markdown.append("## Scope and coverage\n\n").append(escape(coverage)).append("\n\n")
                .append("Dates without a source time are interpreted at the start of that date in ")
                .append(ApplicationTime.zone().getId()).append(". ")
                .append(
                        "Citations validate resource ownership and references, not every analytical conclusion.\n\n");
        for (Map<String, Object> section : sections) {
            markdown.append("\n## ").append(escape(text(section, "heading", 200))).append("\n\n")
                    .append(escape(text(section, "text", 12000))).append("\n\n");
            for (Map<String, Object> citation : objectList(section, "citations", 50)) {
                ResearchArtifact item = evidence.get(text(citation, "evidence_id", 80));
                markdown.append("- Source `").append(item.id()).append("` ")
                        .append(escape(optionalText(citation, "reference", 80)))
                        .append("; SHA-256 `").append(item.checksum()).append("`; retrieved ")
                        .append(retrievedAt(item))
                        .append("; ").append(escape(item.sourceUrl())).append("\n");
            }
        }
        appendList(markdown, "Limitations and unread material", stringList(input, "limitations", 50, 2000));
        appendList(markdown, "Next steps", stringList(input, "next_steps", 30, 2000));
        markdown.append("\n## Evidence coverage\n\n");
        evidence.values().forEach(
                item -> markdown.append("- `").append(item.id()).append("`: ").append(escape(item.coverage()))
                        .append(" (extraction: ").append(item.extractionMethod()).append("; status: ")
                        .append(item.status()).append(")\n"));
        byte[] bytes = markdown.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > properties.maxReportBytes()) {
            throw invalid("Rendered report exceeds configured byte limit");
        }
        requireGeneration(context, start.goalGeneration);
        ResearchArtifact report = new ResearchArtifact(
                id,
                "report",
                context.executionId(),
                context.scope(),
                start.goalGeneration,
                "report-" + id.substring(2, 14) + ".md",
                "text/markdown",
                bytes.length,
                hash(bytes),
                clock.instant(),
                List.copyOf(evidence.keySet()),
                coverage,
                "",
                title,
                "created",
                "deterministic_markdown");
        ResearchArtifact saved = repository
                .save(report, bytes, provenance(context, Map.of()), properties.maxArtifacts());
        // Corrections racing persistence retain the immutable artifact for inspection but never report current creation success.
        requireGeneration(context, start.goalGeneration);
        return Map.of("status", "created", "report", metadata(saved), "receipt_replayed", false);
    }

    /** The configured ceiling for one output file, so capture tools can bound bytes before saving. */
    public int maxOutputFileBytes() {
        return properties.maxOutputFileBytes();
    }

    /** A report or output file this execution may stage for delivery with its final answer. */
    public Optional<ResearchArtifact> ownedDeliverable(String executionId, TrustedScope scope, String id) {
        return repository.find(id).filter(
                item -> DELIVERABLE_KINDS.contains(item.kind()) && item.executionId().equals(executionId)
                        && item.scope().sameOwner(scope));
    }

    /** The stored bytes of an owned research artifact, for copying into an output file; empty when foreign. */
    public Optional<OutputFileSource.SourceFile> ownedSourceFile(ToolRegistry.Context context, String id) {
        return owned(context, id).filter(item -> COPYABLE_KINDS.contains(item.kind())).map(item -> {
            if (item.byteSize() > properties.maxOutputFileBytes()) {
                throw invalid("The source file exceeds the output file byte limit");
            }
            byte[] bytes = repository.bytes(item.id());
            if (bytes.length != item.byteSize() || !hash(bytes).equals(item.checksum())) {
                throw new IllegalStateException("Source artifact integrity mismatch");
            }
            return new OutputFileSource.SourceFile(item.filename(), item.mediaType(), bytes);
        });
    }

    /**
     * Saves bytes the task produced as an immutable output file for the requester. The ID is the accepted
     * call's receipt, so a replayed call returns the file it already saved instead of a second copy. The
     * recorded media type comes from the bytes and the filename, never from model text.
     */
    public ResearchArtifact saveOutputFile(
            ToolRegistry.Context context,
            String filename,
            byte[] bytes,
            String description,
            Map<String, String> provenance) {
        if (context.childRunId() != null) {
            throw invalid("Output files may only be created by the parent task");
        }
        ExecutionSnapshot start = currentGoal(context);
        String id = receiptId(context, "output");
        Optional<ResearchArtifact> replay = owned(context, id)
                .filter(item -> item.kind().equals(OUTPUT_KIND));
        if (replay.isPresent()) {
            if (replay.get().goalGeneration() != start.goalGeneration) {
                throw new ResearchException(
                        "stale_goal",
                        "The output file belongs to a superseded task goal");
            }
            return replay.get();
        }
        String name;
        try {
            name = UploadTypes.filename(filename);
        } catch (IllegalArgumentException rejected) {
            throw invalid(rejected.getMessage());
        }
        if (bytes == null || bytes.length == 0) {
            throw invalid("An output file must not be empty");
        }
        if (bytes.length > properties.maxOutputFileBytes()) {
            throw invalid(
                    "The output file exceeds the limit of " + properties.maxOutputFileBytes() + " bytes");
        }
        var privateData = provenance(context, provenance);
        privateData.put("created_at", clock.instant().toString());
        ResearchArtifact output = new ResearchArtifact(
                id,
                OUTPUT_KIND,
                context.executionId(),
                context.scope(),
                start.goalGeneration,
                name,
                UploadTypes.mediaType(name, bytes),
                bytes.length,
                hash(bytes),
                clock.instant(),
                List.of(),
                bounded(
                        description == null || description.isBlank() ? "Produced by the task" : description,
                        4000),
                "",
                name,
                "created",
                "task_output");
        requireGeneration(context, start.goalGeneration);
        ResearchArtifact saved = repository.save(output, bytes, privateData, properties.maxArtifacts());
        // Corrections racing persistence retain the immutable artifact for inspection but never report current creation success.
        requireGeneration(context, start.goalGeneration);
        return saved;
    }

    /** Validates delegated inputs/results against the original task's immutable resources. */
    @Override
    public void validateChildReferences(ToolRegistry.Context context, List<Map<String, Object>> references) {
        try {
            validateReferences(context, references);
        } catch (RuntimeException rejected) {
            throw rejected;
        } catch (Exception unavailable) {
            throw invalid("Immutable delegated references could not be validated");
        }
    }

    private void validateReferences(ToolRegistry.Context context, List<Map<String, Object>> references)
            throws Exception {
        currentGoal(context);
        if (references == null || references.size() > 50) {
            throw invalid("Too many delegated references");
        }
        for (Map<String, Object> reference : references) {
            if (reference == null || !java.util.Set.of("kind", "content_hash", "id", "reference")
                    .containsAll(reference.keySet())) {
                throw invalid("Invalid delegated reference fields");
            }
            String kind = text(reference, "kind", 16), checksum = text(reference, "content_hash", 128);
            if (!java.util.Set.of("document", "evidence").contains(kind)) {
                throw invalid("Unknown delegated reference kind");
            }
            String id = text(reference, "id", 80);
            ResearchArtifact item = owned(context, id)
                    .filter(value -> kind.equals(value.kind()) && checksum.equals(value.checksum()))
                    .orElseThrow(
                            () -> invalid(
                                    "Delegated reference does not resolve to owned immutable evidence with that content hash"));
            if (reference.containsKey("reference")) {
                String unit = text(reference, "reference", 80);
                if (extraction(item).parts().stream().noneMatch(part -> unit.equals(part.reference()))) {
                    throw invalid(
                            "Citation page/image/chunk reference does not exist in immutable extraction");
                }
            }
        }
        currentGoal(context);
    }

    /** Administrative view of the task's reports and output files, newest first within each kind. */
    public List<ResearchArtifact> adminList(String executionId) {
        executions.get(executionId);
        var result = new ArrayList<>(repository.list(executionId, "report", properties.maxArtifacts()));
        result.addAll(repository.list(executionId, OUTPUT_KIND, properties.maxArtifacts()));
        return List.copyOf(result);
    }

    public Optional<byte[]> adminDownload(String executionId, String reportId) {
        executions.get(executionId);
        return repository.find(reportId)
                .filter(
                        item -> item.executionId().equals(executionId)
                                && DELIVERABLE_KINDS.contains(item.kind()))
                .map(item -> {
                    long limit = item.kind().equals(OUTPUT_KIND)
                            ? properties.maxOutputFileBytes()
                            : properties.maxReportBytes();
                    if (item.byteSize() > limit) {
                        throw new IllegalArgumentException("Research artifact exceeds read limit");
                    }
                    return repository.bytes(item.id());
                });
    }

    /** Administrative view of requester-supplied files with the document each one was extracted into, if any. */
    public List<FileView> adminListFiles(String executionId) {
        var snapshot = executions.get(executionId);
        return ownedUploads(executionId, snapshot.scope).stream().map(file -> {
            ResearchArtifact document = extractedDocument(executionId, snapshot.scope, file.id());
            return new FileView(
                    file,
                    document == null ? null : document.id(),
                    document == null ? null : document.status());
        }).toList();
    }

    public record FileView(ResearchArtifact file, String documentId, String extractionStatus) {}

    public Optional<byte[]> adminDownloadFile(String executionId, String fileId) {
        executions.get(executionId);
        return repository.find(fileId)
                .filter(item -> item.executionId().equals(executionId) && item.kind().equals(UPLOAD_KIND))
                .map(item -> {
                    if (item.byteSize() > uploads.maxFileBytes()) {
                        throw new IllegalArgumentException("Research artifact exceeds read limit");
                    }
                    return repository.bytes(item.id());
                });
    }

    public void deleteExecution(String executionId) {
        repository.deleteExecution(executionId);
    }

    public String retrievedAt(ResearchArtifact artifact) {
        String value = repository.privateMetadata(artifact.id())
                .getOrDefault("retrieved_at", artifact.createdAt().toString());
        try {
            return java.time.Instant.parse(value).toString();
        } catch (java.time.format.DateTimeParseException invalid) {
            return artifact.createdAt().toString();
        }
    }

    public static Map<String, Object> metadata(ResearchArtifact artifact) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", artifact.id());
        result.put("kind", artifact.kind());
        result.put("filename", artifact.filename());
        result.put("media_type", artifact.mediaType());
        result.put("byte_size", artifact.byteSize());
        result.put("checksum", artifact.checksum());
        result.put("created_at", artifact.createdAt().toString());
        result.put("goal_generation", artifact.goalGeneration());
        result.put("evidence_ids", artifact.evidenceIds());
        result.put("coverage", artifact.coverage());
        result.put("source_url", artifact.sourceUrl());
        result.put("title", artifact.title());
        result.put("status", artifact.status());
        result.put("extraction_method", artifact.extractionMethod());
        return result;
    }

    private DocumentExtractor.Extraction extraction(ResearchArtifact artifact) throws Exception {
        if (artifact.kind().equals("evidence")) {
            if (artifact.byteSize() > 4L * properties.maxExtractedChars()) {
                throw new IllegalArgumentException("Research artifact exceeds read limit");
            }
            String text = new String(repository.bytes(artifact.id()), StandardCharsets.UTF_8);
            List<DocumentExtractor.Part> parts = new ArrayList<>();
            for (int offset = 0; offset < text.length(); offset += 4000) {
                parts.add(
                        new DocumentExtractor.Part(
                                "chunk:" + (parts.size() + 1),
                                text.substring(offset, Math.min(text.length(), offset + 4000))));
            }
            return new DocumentExtractor.Extraction(
                    artifact.status(),
                    artifact.extractionMethod(),
                    parts,
                    parts.size(),
                    artifact.coverage());
        }
        return JSON.readValue(
                repository.privateMetadata(artifact.id()).get("extraction"),
                DocumentExtractor.Extraction.class);
    }

    private Optional<ResearchArtifact> owned(ToolRegistry.Context context, String id) {
        return repository.find(id).filter(
                item -> item.executionId().equals(context.executionId())
                        && item.scope().sameOwner(context.scope()));
    }

    private ExecutionSnapshot execution(ToolRegistry.Context context) {
        if (context.childRunId() != null) {
            return currentGoal(context);
        }
        context.cancellation().check();
        ExecutionSnapshot snapshot = executions.get(context.executionId());
        if (!snapshot.scope.sameOwner(context.scope())) {
            throw new ResearchException("not_found", "Owned execution not found");
        }
        if (snapshot.cancellationRequested) {
            throw new java.util.concurrent.CancellationException();
        }
        return snapshot;
    }

    private ExecutionSnapshot currentGoal(ToolRegistry.Context context) {
        try {
            return CallProvenance.current(executions, context).execution();
        } catch (CallProvenance.Rejected rejected) {
            throw new ResearchException(rejected.status(), rejected.getMessage());
        }
    }

    private void requireGeneration(ToolRegistry.Context context, long generation) {
        if (currentGoal(context).goalGeneration != generation) {
            throw new ResearchException("stale_goal", "A task correction superseded this report call");
        }
    }

    private static String receiptId(ToolRegistry.Context context, String operation) {
        if (context.acceptedCallBinding().isBlank()) {
            throw invalid("Accepted call provenance is required");
        }
        String prefix = switch (operation) {
            case "report" -> "r_";
            case "output" -> "f_";
            default -> "d_";
        };
        return prefix + hash(
                (context.executionId() + "\0" + operation + "\0"
                        + (context.childRunId() == null ? "" : context.childRunId() + "\0")
                        + context.acceptedCallBinding()).getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, String> provenance(ToolRegistry.Context context, Map<String, String> extra) {
        Map<String, String> result = new LinkedHashMap<>();
        if (extra != null) {
            extra.forEach((key, value) -> {
                if (key == null || key.length() > 128 || value == null || value.length() > 5_000_000) {
                    throw invalid("Invalid source provenance");
                }
                result.put(key, value);
            });
        }
        result.put("accepted_call_id", context.acceptedCallId());
        result.put("accepted_call_binding", context.acceptedCallBinding());
        // A cached document gets a new call receipt; do not inherit its producer's run locator.
        result.remove("child_run_id");
        if (context.childRunId() != null) {
            result.put("child_run_id", context.childRunId());
        }
        return result;
    }

    public static String hash(byte[] bytes) {
        return Sha256.hex(bytes);
    }

    public static String safeFilename(String value) {
        String safe = value == null
                ? "document"
                : value.replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("^\\.+", "");
        if (safe.isBlank()) {
            safe = "document";
        }
        return safe.substring(0, Math.min(150, safe.length()));
    }

    private static String safeMediaType(String value) {
        return value != null && value.length() <= 150 && value.matches("[a-z0-9.+-]+/[a-z0-9.+-]+")
                ? value
                : "application/octet-stream";
    }

    private static String bounded(String value, int max) {
        return value == null ? "" : value.substring(0, Math.min(value.length(), max));
    }

    private static String text(Map<String, Object> input, String key, int max) {
        if (!(input.get(key) instanceof String value) || value.isBlank() || value.length() > max) {
            throw invalid("Invalid " + key);
        }
        return value;
    }

    private static String optionalText(Map<String, Object> input, String key, int max) {
        return input.containsKey(key) ? text(input, key, max) : "";
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objectList(Map<String, Object> input, String key, int max) {
        Object value = input.getOrDefault(key, List.of());
        if (!(value instanceof List<?> values) || values.size() > max
                || values.stream().anyMatch(item -> !(item instanceof Map))) {
            throw invalid("Invalid " + key);
        }
        return (List<Map<String, Object>>) value;
    }

    private static List<String> stringList(Map<String, Object> input, String key, int max, int length) {
        Object value = input.getOrDefault(key, List.of());
        if (!(value instanceof List<?> values) || values.size() > max || values.stream()
                .anyMatch(item -> !(item instanceof String text) || text.length() > length)) {
            throw invalid("Invalid " + key);
        }
        return values.stream().map(String.class::cast).toList();
    }

    private static int number(Map<String, Object> input, String key, int fallback, int min, int max) {
        if (!input.containsKey(key)) {
            return fallback;
        }
        if (!(input.get(key) instanceof Number number) || number.doubleValue() != number.intValue()
                || number.intValue() < min || number.intValue() > max) {
            throw invalid("Invalid " + key);
        }
        return number.intValue();
    }

    private static String escape(String text) {
        // Markdown is downloaded as an attachment; escape HTML and markdown link/image syntax to prevent model-supplied active links.
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\\", "\\\\")
                .replace("[", "\\[").replace("]", "\\]").replace("`", "\\`");
    }

    private static String cell(String text) {
        return escape(text).replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }

    private static void appendList(StringBuilder result, String heading, List<String> values) {
        result.append("\n## ").append(heading).append("\n\n");
        if (values.isEmpty()) {
            result.append("Not supplied.\n");
        }
        values.forEach(value -> result.append("- ").append(escape(value)).append("\n"));
    }

    private static Map<String, Object> status(String value) {
        return Map.of("status", value);
    }

    private static ResearchException invalid(String message) {
        return new ResearchException("invalid_request", message);
    }

    public static final class ResearchException extends RuntimeException {

        private final String status;

        public ResearchException(String status, String message) {
            super(message);
            this.status = status;
        }

        public String status() {
            return status;
        }
    }

    @Override
    public void close() {
        // The downloader is a shared Spring-owned bean with its own lifecycle.
        parsers.shutdownNow();
    }
}
