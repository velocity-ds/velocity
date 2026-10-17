package net.velocity.agent.research;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipInputStream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;

import net.velocity.agent.CancellationHandle;
import net.velocity.inference.InferenceDeadlineScope;

/** Passive bounded extraction: no scripts, external entities, macros, images/OCR, or linked resources. */
public final class DocumentExtractor {

    public record Part(String reference, String text) {}

    public record Extraction(
            String status,
            String method,
            List<Part> parts,
            int totalUnits,
            String coverage) {

        public Extraction {
            parts = List.copyOf(parts);
        }
    }

    private final ResearchProperties properties;

    public DocumentExtractor(ResearchProperties properties) {
        this.properties = properties;
    }

    public Extraction extract(
            byte[] bytes,
            String mediaType,
            String filename,
            CancellationHandle cancellation) {
        long deadline = System.nanoTime()
                + InferenceDeadlineScope.remainingOr(properties.extractionTimeout()).toNanos();
        check(cancellation, deadline);
        mediaType = mediaType == null ? "" : mediaType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        String extension = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        try {
            if (pdf(bytes)) {
                return unavailable(
                        "PDF reading requires the selected inference connector's vision support and the configured page renderer");
            }
            if (extension.endsWith(".docx") && bytes.length >= 8 && bytes[0] == (byte) 0xd0
                    && bytes[1] == (byte) 0xcf) {
                return new Extraction(
                        "encrypted",
                        "office_container",
                        List.of(),
                        0,
                        "Encrypted/legacy Office container; document contents were not read");
            }
            if (bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K') {
                if (!wordFormat(mediaType, extension)) {
                    return new Extraction(
                            "unsupported",
                            "none",
                            List.of(),
                            0,
                            "ZIP archives and spreadsheets are unsupported");
                }
                return docx(bytes, cancellation, deadline);
            }
            String text = decode(bytes);
            check(cancellation, deadline);
            if (html(text) || htmlFormat(mediaType, extension)) {
                if (accessPage(text)) {
                    return new Extraction(
                            "unavailable",
                            "html",
                            List.of(),
                            0,
                            "Login, CAPTCHA, access-denied, or error HTML; document contents were not read");
                }
                if (text.indexOf('\0') >= 0) {
                    return unavailable("Binary content masquerades as HTML");
                }
                Extraction prepared = textParts(text, "html_source", cancellation, deadline);
                return new Extraction(
                        prepared.status(),
                        prepared.method(),
                        prepared.parts(),
                        prepared.totalUnits(),
                        "HTML source supplied as untrusted text; markup, scripts, styles and hidden content are retained. "
                                + "No browser rendering, script execution or linked resource retrieval; "
                                + prepared.coverage());
            }
            if (textFormat(mediaType, extension)) {
                if (text.indexOf('\0') >= 0) {
                    return unavailable("Binary content masquerades as text");
                }
                return textParts(
                        text,
                        extension.endsWith(".csv") ? "csv_text" : "utf8_text",
                        cancellation,
                        deadline);
            }
            return new Extraction(
                    "unsupported",
                    "none",
                    List.of(),
                    0,
                    "Unsupported format; contents were not analyzed");
        } catch (ExtractionLimit exceeded) {
            return new Extraction(
                    "partial",
                    "bounded_parser",
                    List.of(),
                    0,
                    exceeded.getMessage() + "; no complete extracted passage retained");
        } catch (java.util.concurrent.CancellationException cancelled) {
            throw cancelled;
        } catch (Exception malformed) {
            return unavailable("Malformed, unsupported, or unreadable document; no text extracted");
        }
    }

    public static boolean pdf(byte[] bytes) {
        return starts(bytes, "%PDF-");
    }

    static boolean wordFormat(String mediaType, String filename) {
        return filename.endsWith(".docx") || mediaType.contains("wordprocessingml");
    }

    static boolean htmlFormat(String mediaType, String filename) {
        return mediaType.equals("text/html") || mediaType.equals("application/xhtml+xml")
                || filename.endsWith(".html") || filename.endsWith(".htm");
    }

    static boolean textFormat(String mediaType, String filename) {
        return mediaType.startsWith("text/")
                || java.util.Set.of(
                        "application/json",
                        "application/xml",
                        "application/yaml",
                        "application/x-yaml",
                        "application/rtf").contains(mediaType)
                || List.of(".txt", ".csv", ".md", ".json", ".xml", ".yaml", ".yml", ".tsv", ".log", ".rtf")
                        .stream().anyMatch(filename::endsWith);
    }

    private Extraction docx(byte[] bytes, CancellationHandle cancellation, long deadline) throws Exception {
        byte[] documentXml = null;
        int entries = 0;
        long expanded = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                check(cancellation, deadline);
                if (++entries > properties.maxArchiveEntries()) {
                    throw new ExtractionLimit("DOCX entry limit reached");
                }
                String name = entry.getName();
                if (name.startsWith("/") || name.contains("\\") || name.contains("../")
                        || name.matches("^[A-Za-z]:.*")) {
                    throw new IOException("Invalid DOCX entry");
                }
                ByteArrayOutputStream selected = name.equals("word/document.xml")
                        ? new ByteArrayOutputStream()
                        : null;
                byte[] buffer = new byte[8192];
                int count;
                while ((count = zip.read(buffer)) >= 0) {
                    check(cancellation, deadline);
                    expanded += count;
                    if (expanded > properties.maxExpandedBytes()) {
                        throw new ExtractionLimit("DOCX expansion limit reached");
                    }
                    if (selected != null) {
                        selected.write(buffer, 0, count);
                    }
                }
                if (selected != null) {
                    if (documentXml != null) {
                        throw new IOException("Duplicate DOCX main document");
                    }
                    documentXml = selected.toByteArray();
                }
            }
        }
        if (documentXml == null) {
            return unavailable("ZIP is not a DOCX main document");
        }
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> {
            throw new javax.xml.stream.XMLStreamException("External entity forbidden");
        });
        var reader = factory.createXMLStreamReader(new ByteArrayInputStream(documentXml));
        BoundedWriter text = new BoundedWriter(properties.maxExtractedChars(), cancellation, deadline);
        boolean reading = false, truncated = false, bodySeen = false, inBody = false;
        int depth = 0;
        String documentNamespace = null;
        try {
            while (reader.hasNext()) {
                check(cancellation, deadline);
                int event = reader.next();
                if (event == XMLStreamConstants.DTD) {
                    throw new IOException("DTD forbidden");
                }
                boolean elementEvent = event == XMLStreamConstants.START_ELEMENT
                        || event == XMLStreamConstants.END_ELEMENT;
                String namespace = elementEvent ? reader.getNamespaceURI() : "";
                boolean word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
                        .equals(namespace)
                        || "http://purl.oclc.org/ooxml/wordprocessingml/main".equals(namespace);
                String local = elementEvent && word ? reader.getLocalName() : "";
                if (event == XMLStreamConstants.START_ELEMENT) {
                    depth++;
                    if (depth == 1) {
                        if (!word || !local.equals("document")) {
                            throw new IOException("Invalid DOCX document root");
                        }
                        documentNamespace = namespace;
                    }
                    if (local.equals("body")) {
                        if (depth != 2 || bodySeen || !namespace.equals(documentNamespace)) {
                            throw new IOException("Invalid DOCX body");
                        }
                        bodySeen = true;
                        inBody = true;
                    }
                }
                if (!truncated && inBody) {
                    try {
                        if (event == XMLStreamConstants.START_ELEMENT && local.equals("t")) {
                            reading = true;
                        }
                        if (event == XMLStreamConstants.CHARACTERS && reading) {
                            text.write(reader.getText());
                        }
                        if (event == XMLStreamConstants.END_ELEMENT) {
                            if (local.equals("t")) {
                                reading = false;
                            }
                            if (local.equals("p") || local.equals("tr")) {
                                text.write("\n");
                            }
                            if (local.equals("tc")) {
                                text.write("\t");
                            }
                        }
                        if (event == XMLStreamConstants.START_ELEMENT && local.equals("tab")) {
                            text.write("\t");
                        }
                        if (event == XMLStreamConstants.START_ELEMENT && local.equals("br")) {
                            text.write("\n");
                        }
                    } catch (ExtractionLimit limit) {
                        truncated = true;
                    }
                }
                if (event == XMLStreamConstants.END_ELEMENT) {
                    if (local.equals("body")) {
                        inBody = false;
                        reading = false;
                    }
                    depth--;
                }
            }
        } finally {
            reader.close();
        }
        if (!bodySeen) {
            return unavailable("DOCX main document has no WordprocessingML body");
        }
        Extraction result = textParts(text.toString(), "docx_main_xml", cancellation, deadline);
        return new Extraction(
                truncated ? "partial" : result.status(),
                result.method(),
                result.parts(),
                result.totalUnits(),
                "Main DOCX paragraphs/tables only; images, headers, footers, comments, embedded files and tracked-change semantics are not analyzed"
                        + (truncated ? "; text limit reached" : ""));
    }

    private Extraction textParts(String text, String method, CancellationHandle cancellation, long deadline) {
        check(cancellation, deadline);
        boolean truncated = text.length() > properties.maxExtractedChars();
        text = text.substring(0, Math.min(text.length(), properties.maxExtractedChars()));
        var parts = new ArrayList<Part>();
        for (int offset = 0; offset < text.length(); offset += 4000) {
            check(cancellation, deadline);
            parts.add(
                    new Part(
                            "chunk:" + (parts.size() + 1),
                            text.substring(offset, Math.min(text.length(), offset + 4000))));
        }
        return new Extraction(
                truncated ? "partial" : "successful",
                method,
                parts,
                parts.size(),
                truncated
                        ? "Textual representation truncated at configured preparation limit"
                        : "Prepared textual representation only; layout and non-text content not analyzed");
    }

    public static boolean html(String value) {
        return value.stripLeading().toLowerCase(Locale.ROOT).matches(
                "(?s)^(<!doctype\\s+html|<html|<head|<body|<form|<title|<input|<div|<h[1-6]|<table).*");
    }

    public static boolean accessPage(String value) {
        return HtmlAccessDetector.isAccessPage(value);
    }

    private static String decode(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static boolean starts(byte[] bytes, String signature) {
        return bytes.length >= signature.length()
                && new String(bytes, 0, signature.length(), StandardCharsets.US_ASCII).equals(signature);
    }

    private static Extraction unavailable(String coverage) {
        return new Extraction("unavailable", "none", List.of(), 0, coverage);
    }

    private static void check(CancellationHandle cancellation, long deadline) {
        cancellation.check();
        if (System.nanoTime() >= deadline) {
            throw new ExtractionLimit("Extraction processing deadline reached");
        }
    }

    private static final class ExtractionLimit extends RuntimeException {

        ExtractionLimit(String message) {
            super(message);
        }
    }

    private static final class BoundedWriter extends Writer {

        private final StringBuilder text = new StringBuilder();
        private final int maximum;
        private final CancellationHandle cancellation;
        private final long deadline;

        BoundedWriter(int maximum, CancellationHandle cancellation, long deadline) {
            this.maximum = maximum;
            this.cancellation = cancellation;
            this.deadline = deadline;
        }

        @Override
        public void write(char[] buffer, int offset, int length) {
            check(cancellation, deadline);
            int accepted = Math.min(length, maximum - text.length());
            text.append(buffer, offset, accepted);
            if (accepted != length) {
                throw new ExtractionLimit("Text limit reached");
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }
}
