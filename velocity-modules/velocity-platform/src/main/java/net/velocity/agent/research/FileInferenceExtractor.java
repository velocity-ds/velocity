package net.velocity.agent.research;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import net.velocity.agent.BoundedInference;
import net.velocity.agent.ContextSizingPolicy;
import net.velocity.agent.JsonSchema;
import net.velocity.agent.PinnedInference;
import net.velocity.agent.ToolRegistry;
import net.velocity.agent.persistence.ExecutionRepository;
import net.velocity.inference.InferenceDeadlineScope;
import net.velocity.inference.InferenceProfileRegistry;
import net.velocity.inference.InferenceService;
import net.velocity.inference.InferenceUsageScope;
import net.velocity.inference.InputTokenCountException;
import net.velocity.inference.SystemPrompts;
import net.velocity.inference.objects.InferenceCapability;
import net.velocity.inference.objects.InferenceContent;
import net.velocity.inference.objects.InferenceEvent;
import net.velocity.inference.objects.InferenceMessage;
import net.velocity.inference.objects.InferenceOptions;
import net.velocity.inference.objects.InferenceRequest;
import net.velocity.inference.objects.InferenceResponse;
import net.velocity.inference.objects.InferenceResponseException;
import net.velocity.usage.UsageLedger;

/** Bounded file interpretation through the owning execution's selected inference profile. */
public final class FileInferenceExtractor {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    /** Bounds on the model-reported limitation list, stated in the output schema and applied to the response. */
    private static final int MAX_REPORTED_LIMITATIONS = 8;
    private static final int MAX_REPORTED_LIMITATION_CHARS = 256;
    private final InferenceProfileRegistry profiles;
    private final ExecutionRepository executions;
    private final PdfPageRenderer renderer;
    private final ResearchProperties research;
    private final FileProcessingProperties properties;
    private final DocumentExtractor preparer;

    public FileInferenceExtractor(
            PdfPageRenderer renderer,
            ResearchProperties research,
            FileProcessingProperties properties,
            InferenceProfileRegistry profiles,
            ExecutionRepository executions) {
        this.renderer = renderer;
        this.research = research;
        this.properties = properties;
        this.profiles = java.util.Objects.requireNonNull(profiles);
        this.executions = java.util.Objects.requireNonNull(executions);
        this.preparer = new DocumentExtractor(research);
    }

    private record SelectedInference(InferenceService service, long inputAllowance, long maxOutputTokens) {}

    private SelectedInference selected(ToolRegistry.Context context) {
        var execution = executions.get(context.executionId());
        var binding = execution.activeInferenceBinding();
        if (context.childRunId() != null) {
            var child = execution.childRuns.get(context.childRunId());
            if (child == null) {
                return null;
            }
            // A child is a separate native conversation with its own pinned binding.
            binding = child.inferenceBinding;
        }
        var resolution = PinnedInference.resolve(binding, profiles);
        if (!resolution.available()) {
            return null;
        }
        return new SelectedInference(
                resolution.profile().service(),
                binding.inputAllowance(),
                binding.maxOutputTokens());
    }

    public long timeoutMillis() {
        return InferenceDeadlineScope.remainingOr(properties.timeout()).toMillis();
    }

    public DocumentExtractor.Extraction extract(
            byte[] bytes,
            String mediaType,
            String filename,
            ToolRegistry.Context context,
            Runnable beforeUnit) throws Exception {
        context.cancellation().check();
        Format format = format(bytes, mediaType, filename);
        if (format == Format.UNSUPPORTED) {
            return result(
                    format,
                    "unsupported",
                    List.of(),
                    0,
                    "Unsupported file format; contents were not analyzed");
        }
        SelectedInference selected = selected(context);
        if (selected == null) {
            return result(
                    format,
                    "unavailable",
                    List.of(),
                    0,
                    "The owning execution's inference profile is unavailable or incompatible");
        }
        if (!selected.service().available()
                || !selected.service().capabilities().containsAll(
                        Set.of(
                                InferenceCapability.TEXT,
                                InferenceCapability.STRUCTURED_OUTPUT,
                                InferenceCapability.STREAMING))) {
            return result(
                    format,
                    "unavailable",
                    List.of(),
                    0,
                    "The selected inference connector must support text, streaming and structured output");
        }
        if (format.vision && !selected.service().capabilities().contains(InferenceCapability.VISION)) {
            return result(
                    format,
                    "unavailable",
                    List.of(),
                    0,
                    "The selected inference connector must support vision for this file format");
        }
        long deadline = System.nanoTime()
                + InferenceDeadlineScope.remainingOr(properties.timeout()).toNanos();
        // Child call identity survives recovery; root requests retain their existing usage-source contract.
        String operation = binding(context) + (context.childRunId() == null ? ":" + UUID.randomUUID() : "");
        var parts = new ArrayList<DocumentExtractor.Part>();
        var limitations = new ArrayList<String>();
        int units = 0;
        boolean partial = false;
        try (var prepared = prepare(format, bytes, mediaType, filename, context, deadline)) {
            check(context, deadline);
            units = prepared.units;
            limitations.add(prepared.coverage);
            if (!Set.of("successful", "partial").contains(prepared.status) || units < 1) {
                return result(format, prepared.status, List.of(), units, prepared.coverage);
            }
            int limit = Math.min(units, properties.maxUnits());
            int remaining = research.maxExtractedChars();
            partial = limit < units || "partial".equals(prepared.status);
            if (limit < units) {
                limitations.add("Configured processing unit limit reached");
            }
            Map<String, Object> schema = schema();
            for (int unit = 1; unit <= limit; unit++) {
                check(context, deadline);
                List<InferenceContent> content = prepared.input.read(unit);
                check(context, deadline);
                var request = new InferenceRequest(
                        List.of(
                                new InferenceMessage(
                                        InferenceMessage.Role.USER,
                                        content)),
                        SystemPrompts.FILE_CONTENT_TRANSCRIPTION,
                        InferenceOptions.defaults().withOutputSchema(schema));
                try {
                    beforeUnit.run();
                } catch (ToolRegistry.ProviderRequestLimitException budgetExhausted) {
                    throw new UnitBudgetReached();
                } catch (ResearchService.ResearchException rejected) {
                    if ("limit_reached".equals(rejected.status())) {
                        throw new UnitBudgetReached();
                    }
                    throw new AdmissionFailure(rejected);
                } catch (RuntimeException rejected) {
                    throw new AdmissionFailure(rejected);
                }
                check(context, deadline);
                InferenceResponse response = infer(
                        context,
                        selected,
                        request,
                        operation + ":" + format.reference + ":" + unit,
                        deadline);
                check(context, deadline);
                Bounded bounded = bound(JSON.readValue(response.text(), Object.class));
                if (bounded.textTruncated()) {
                    partial = true;
                    limitations.add(
                            "Unit " + unit + " transcription was cut at the configured per-unit text limit");
                }
                Object parsed = bounded.value();
                JsonSchema.validate(schema, parsed);
                Map<?, ?> value = (Map<?, ?>) parsed;
                String text = (String) value.get("text");
                boolean complete = Boolean.TRUE.equals(value.get("complete")) && response.stopReason() != null
                        && Set.of("completed", "end_turn", "stop").contains(response.stopReason());
                if (!complete) {
                    partial = true;
                    limitations.add("Unit " + unit + " transcription is incomplete");
                }
                List<?> reported = (List<?>) value.get("limitations");
                if (!reported.isEmpty()) {
                    partial = true;
                    // Model-reported limitations are untrusted data, bounded independently of the page text.
                    if (limitations.size() < 12) {
                        limitations.add(
                                "Unit " + unit + " model-reported limitation (untrusted): "
                                        + reported.getFirst());
                    }
                }
                if (text.length() > remaining) {
                    text = text.substring(0, remaining);
                    partial = true;
                }
                parts.add(new DocumentExtractor.Part(format.reference + ":" + unit, text));
                remaining -= text.length();
                if (remaining == 0) {
                    if (unit < units || text.length() < ((String) value.get("text")).length()) {
                        partial = true;
                    }
                    limitations.add("Configured total transcription text limit reached");
                    break;
                }
            }
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (AdmissionFailure rejected) {
            throw rejected.failure;
        } catch (UsageFailure unavailable) {
            throw unavailable.failure;
        } catch (UnitBudgetReached exhausted) {
            partial = true;
            limitations.add("Task token budget prevents further file inference");
        } catch (PdfPageRenderer.PdfRenderException failure) {
            if (parts.isEmpty() && "encrypted".equals(failure.status())) {
                return result(
                        format,
                        "encrypted",
                        List.of(),
                        units,
                        "Encrypted PDF was not sent to inference");
            }
            partial = true;
            limitations.add(rendererMessage(failure));
        } catch (DeadlineReached expired) {
            partial = true;
            limitations.add("File processing reached its caller deadline");
        } catch (UnitFailure bounded) {
            partial = true;
            limitations.add(authoredMessage(bounded));
        } catch (Exception failed) {
            context.cancellation().check();
            partial = true;
            limitations.add(
                    "A file unit could not be prepared, transcribed, or its structured response could not be validated");
        }
        return result(
                format,
                parts.isEmpty() ? "unavailable" : partial ? "partial" : "successful",
                parts,
                units,
                String.join("; ", limitations));
    }

    private Prepared prepare(
            Format format,
            byte[] bytes,
            String mediaType,
            String filename,
            ToolRegistry.Context context,
            long deadline) throws Exception {
        if (format == Format.PDF) {
            var document = renderer.open(bytes, context.cancellation(), deadline);
            return new Prepared(
                    document.encrypted()
                            ? "encrypted"
                            : document.pageCount() < 1 ? "unavailable" : "successful",
                    document.pageCount(),
                    document.encrypted()
                            ? "Encrypted PDF was not sent to inference"
                            : "Only rendered pages were prepared; embedded files, annotations and content outside rendered pages were not analyzed",
                    unit -> {
                        byte[] png = document.render(unit);
                        if (png.length == 0 || png.length > properties.maxPageImageBytes()) {
                            throw new UnitFailure("Rendered page exceeds the configured image byte limit");
                        }
                        return List.of(
                                new InferenceContent.Image(
                                        Base64.getEncoder().encodeToString(png),
                                        "image/png"),
                                new InferenceContent.Text(
                                        "PDF page " + unit + " of " + document.pageCount()));
                    },
                    document::close);
        }
        if (format == Format.IMAGE) {
            String imageType = imageType(bytes);
            return new Prepared(
                    "successful",
                    1,
                    "Single PNG/JPEG image only; metadata and any additional frames were not analyzed",
                    unit -> List.of(
                            new InferenceContent.Image(Base64.getEncoder().encodeToString(bytes), imageType),
                            new InferenceContent.Text("Image 1 of 1")),
                    () -> {});
        }
        var decoded = preparer.extract(bytes, mediaType, filename, context.cancellation());
        var source = decoded.parts();
        if (source.isEmpty() && "successful".equals(decoded.status())) {
            source = List.of(new DocumentExtractor.Part("chunk:1", ""));
        }
        var chunks = source;
        boolean htmlSource = "html_source".equals(decoded.method());
        boolean fragmentedHtml = htmlSource && chunks.size() > 1;
        String coverage = "Input preparation using " + decoded.method() + ": " + decoded.coverage()
                + (fragmentedHtml
                        ? " Fragmented HTML source: markup may open or close in other chunks; rendering and hidden-content visibility "
                                + "cannot be established from isolated chunks. Interpretation remains partial."
                        : "");
        return new Prepared(
                fragmentedHtml ? "partial" : decoded.status(),
                chunks.size(),
                coverage,
                unit -> List.of(
                        new InferenceContent.Text(
                                (htmlSource ? "HTML source chunk " : "File chunk ") + unit + " of "
                                        + chunks.size()
                                        + (fragmentedHtml
                                                ? ". " + SystemPrompts.FILE_HTML_FRAGMENT_CONTEXT
                                                : "")),
                        new InferenceContent.Text(chunks.get(unit - 1).text())),
                () -> {});
    }

    private String imageType(byte[] bytes) throws Exception {
        if (bytes.length > properties.maxPageImageBytes()) {
            throw new UnitFailure("Image exceeds its configured byte limit");
        }
        // Read dimensions without allocating a raster. Both input bytes and metadata-reader work stay bounded by the file deadline.
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new UnitFailure("Image is malformed or unsupported");
            }
            var reader = readers.next();
            try {
                String type = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!Set.of("png", "jpeg", "jpg").contains(type)) {
                    throw new UnitFailure("Unsupported image format");
                }
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > properties.pageDimension()
                        || height > properties.pageDimension()) {
                    throw new UnitFailure("Image dimensions exceed configured processing bounds");
                }
                return type.equals("png") ? "image/png" : "image/jpeg";
            } finally {
                reader.dispose();
            }
        }
    }

    private static Format format(byte[] bytes, String mediaType, String filename) {
        String media = mediaType == null ? "" : mediaType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (DocumentExtractor.pdf(bytes)) {
            return Format.PDF;
        }
        if (bytes.length >= 8 && bytes[0] == (byte) 137 && bytes[1] == 'P' && bytes[2] == 'N'
                && bytes[3] == 'G'
                || bytes.length >= 3 && bytes[0] == (byte) 255 && bytes[1] == (byte) 216
                        && bytes[2] == (byte) 255
                || Set.of("image/png", "image/jpeg").contains(media) || name.endsWith(".png")
                || name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return Format.IMAGE;
        }
        if (DocumentExtractor.textFormat(media, name) || DocumentExtractor.wordFormat(media, name)
                || DocumentExtractor.htmlFormat(media, name)
                || DocumentExtractor
                        .html(new String(bytes, 0, Math.min(bytes.length, 128), StandardCharsets.UTF_8))) {
            return Format.TEXT;
        }
        return Format.UNSUPPORTED;
    }

    private InferenceResponse infer(
            ToolRegistry.Context context,
            SelectedInference selected,
            InferenceRequest request,
            String source,
            long deadline) {
        InferenceResponse.Usage observed = null;
        boolean finalUsage = false;
        check(context, deadline);
        long estimatedInput;
        try {
            var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
            try {
                var estimate = BoundedInference.count(
                        request,
                        request.messages(),
                        List.of(),
                        () -> selected.service().countInputTokens(request, () -> check(context, deadline)),
                        context.cancellation(),
                        executor,
                        () -> java.util.concurrent.TimeUnit.NANOSECONDS
                                .toMillis(Math.max(0, deadline - System.nanoTime())),
                        () -> check(context, deadline),
                        JSON.writeValueAsBytes(request.options().outputSchema()).length);
                estimatedInput = estimate.estimatedInputTokens();
            } finally {
                executor.shutdownNow();
            }
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException();
        } catch (DeadlineReached expired) {
            throw expired;
        } catch (java.util.concurrent.TimeoutException expired) {
            throw new DeadlineReached();
        } catch (InputTokenCountException failed) {
            // The exception retains no provider error body, only whether the failure was transient.
            throw new UnitFailure(
                    failed.transientFailure()
                            ? "The inference provider was unavailable to size the file transcription request"
                            : "The inference provider rejected the file transcription request");
        } catch (Exception invalid) {
            throw new UnitFailure("File inference request size could not be estimated");
        }
        if (estimatedInput > selected.inputAllowance()) {
            throw new UnitFailure(
                    "File inference request exceeds the selected profile's safe input allowance");
        }
        long reservation = ContextSizingPolicy.reserve(estimatedInput, selected.maxOutputTokens());
        try {
            context.admitProviderRequest(source, reservation);
        } catch (ToolRegistry.ProviderRequestLimitException budgetExhausted) {
            throw new UnitBudgetReached();
        } catch (ResearchService.ResearchException rejected) {
            if ("limit_reached".equals(rejected.status())) {
                throw new UnitBudgetReached();
            }
            throw new AdmissionFailure(rejected);
        } catch (RuntimeException rejected) {
            throw new AdmissionFailure(rejected);
        }
        usage(context, source, null, true);
        check(context, deadline);
        var owner = context.scope().ownerAccountId();
        try (var accounting = InferenceUsageScope.open(
                new UsageLedger.Context(
                        owner,
                        context.executionId(),
                        context.executionId(),
                        "attachment",
                        context.executionId(),
                        context.childRunId() == null
                                ? source
                                : "delegated:" + context.childRunId() + ":" + source));
                var stream = selected.service().stream(request, () -> check(context, deadline))) {
            var iterator = stream.events().iterator();
            long emitted = 0;
            while (true) {
                check(context, deadline);
                if (!iterator.hasNext()) {
                    throw new UnitFailure("Model stream ended without a complete response");
                }
                InferenceEvent event = iterator.next();
                if (event.response() != null && event.response().usage() != null) {
                    observed = event.response().usage();
                    // Observable usage is charged immediately; the request remains uncertain until completion.
                    usage(context, source, observed, true);
                }
                if (event.type() == InferenceEvent.Type.TEXT_DELTA && event.delta() != null) {
                    emitted += event.delta().length();
                    if (emitted > maxJsonChars()) {
                        throw new UnitFailure("Model output exceeded its bounded response size");
                    }
                }
                if (event.type() == InferenceEvent.Type.COMPLETE) {
                    InferenceResponse response = event.response();
                    if (response == null) {
                        throw new UnitFailure("Model stream returned no response");
                    }
                    finalUsage = response.usage() != null;
                    if (response.text().length() > maxJsonChars()) {
                        throw new UnitFailure("Model output exceeded its bounded response size");
                    }
                    check(context, deadline);
                    return response;
                }
                check(context, deadline);
            }
        } catch (InferenceResponseException failed) {
            if (failed.usage() != null) {
                observed = failed.usage();
                finalUsage = true;
            }
            throw failed;
        } finally {
            usage(context, source, observed, !finalUsage);
        }
    }

    /**
     * The transcription schema. A provider may enforce fewer of its constraints than it states (Claude strips
     * the length and item bounds from the wire schema), so the response is bounded here before validation.
     */
    private Map<String, Object> schema() {
        return Map.of(
                "type",
                "object",
                "properties",
                Map.of(
                        "text",
                        Map.of("type", "string", "maxLength", properties.maxOutputCharsPerUnit()),
                        "complete",
                        Map.of("type", "boolean"),
                        "limitations",
                        Map.of(
                                "type",
                                "array",
                                "maxItems",
                                MAX_REPORTED_LIMITATIONS,
                                "items",
                                Map.of("type", "string", "maxLength", MAX_REPORTED_LIMITATION_CHARS))),
                "required",
                List.of("text", "complete", "limitations"),
                "additionalProperties",
                false);
    }

    /**
     * Cuts the untrusted response to the schema's size bounds: text at the configured per-unit limit, so a long
     * page keeps its first part instead of failing the unit, and limitation entries by count and length. Any
     * other schema violation is left for validation to reject.
     */
    private Bounded bound(Object parsed) {
        if (!(parsed instanceof Map<?, ?> fields)) {
            return new Bounded(parsed, false);
        }
        var copy = new LinkedHashMap<String, Object>();
        fields.forEach((key, value) -> copy.put(String.valueOf(key), value));
        boolean truncated = false;
        if (fields.get("text") instanceof String text) {
            String cut = cut(text, properties.maxOutputCharsPerUnit());
            truncated = cut.length() < text.length();
            copy.put("text", cut);
        }
        if (fields.get("limitations") instanceof List<?> reported) {
            copy.put(
                    "limitations",
                    reported.stream()
                            .limit(MAX_REPORTED_LIMITATIONS)
                            .map(
                                    item -> item instanceof String entry
                                            ? cut(entry, MAX_REPORTED_LIMITATION_CHARS)
                                            : item)
                            .toList());
        }
        return new Bounded(copy, truncated);
    }

    /** Validation counts code points, so the cut must too. */
    private static String cut(String text, int maxCodePoints) {
        if (text.codePointCount(0, text.length()) <= maxCodePoints) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, maxCodePoints));
    }

    private record Bounded(Object value, boolean textTruncated) {}

    private long maxJsonChars() {
        return properties.maxOutputCharsPerUnit() * 6L + 20_000;
    }

    private static void check(ToolRegistry.Context context, long deadline) {
        context.cancellation().check();
        if (System.nanoTime() >= deadline) {
            throw new DeadlineReached();
        }
    }

    private static void usage(
            ToolRegistry.Context context,
            String source,
            InferenceResponse.Usage usage,
            boolean missing) {
        try {
            context.reportUsage(source, usage, missing);
        } catch (RuntimeException failure) {
            throw new UsageFailure(failure);
        }
    }

    private static String rendererMessage(PdfPageRenderer.PdfRenderException failure) {
        // Only this typed renderer exception contains authored diagnostics; never expose process output or arbitrary errors.
        return authoredMessage(failure);
    }

    private static String authoredMessage(RuntimeException failure) {
        String message = failure.getMessage() == null
                ? ""
                : failure.getMessage().replaceAll("[\\p{Cntrl}]+", " ").strip();
        return message.isBlank()
                ? "A file unit could not be processed within the configured bounds"
                : message.substring(0, Math.min(message.length(), 512));
    }

    private static DocumentExtractor.Extraction result(
            Format format,
            String status,
            List<DocumentExtractor.Part> parts,
            int units,
            String limitation) {
        String coverage = "Model transcription of " + parts.size() + " of " + units + " " + format.units
                + " using the selected inference connector; may omit or misread content and is not verified against the original file. "
                + "References identify prepared source units and are assigned by the application."
                + (limitation.isBlank() ? "" : " " + limitation);
        return new DocumentExtractor.Extraction(
                status,
                format.method,
                parts,
                units,
                coverage.substring(0, Math.min(coverage.length(), 4000)));
    }

    private static String binding(ToolRegistry.Context context) {
        try {
            return "file:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(
                                    (context.executionId() + ":"
                                            + (context.childRunId() == null ? "" : context.childRunId() + ":")
                                            + context.acceptedCallId() + ":" + context.acceptedCallBinding())
                                            .getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static final class DeadlineReached extends RuntimeException {}

    private static final class UnitBudgetReached extends RuntimeException {}

    private static final class UnitFailure extends RuntimeException {

        UnitFailure(String message) {
            super(message);
        }
    }

    @FunctionalInterface
    private interface UnitReader {

        List<InferenceContent> read(int unit) throws Exception;
    }

    private record Prepared(String status, int units, String coverage, UnitReader input, Runnable closer)
            implements
                AutoCloseable {

        @Override
        public void close() {
            closer.run();
        }
    }

    private enum Format {

        PDF("inference_pdf_vision", "page", "rendered PDF pages", true), IMAGE("inference_image_vision",
                "image", "images", true), TEXT("inference_text", "chunk", "prepared source chunks",
                        false), UNSUPPORTED("none", "unit", "units", false);

        final String method, reference, units;
        final boolean vision;

        Format(String method, String reference, String units, boolean vision) {
            this.method = method;
            this.reference = reference;
            this.units = units;
            this.vision = vision;
        }
    }

    private static final class AdmissionFailure extends RuntimeException {

        final RuntimeException failure;

        AdmissionFailure(RuntimeException failure) {
            this.failure = failure;
        }
    }

    private static final class UsageFailure extends RuntimeException {

        final RuntimeException failure;

        UsageFailure(RuntimeException failure) {
            this.failure = failure;
        }
    }
}
