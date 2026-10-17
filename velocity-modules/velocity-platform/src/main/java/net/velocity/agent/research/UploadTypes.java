package net.velocity.agent.research;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.velocity.agent.config.UploadProperties;
import net.velocity.agent.state.UploadedFile;

/**
 * Admission rules for requester-supplied files. The recorded media type comes from the bytes and the
 * filename, never from the client's declaration alone, so a disguised file is stored as what it is.
 */
final class UploadTypes {

    private static final Set<String> RESERVED = Set.of(
            "CON",
            "PRN",
            "AUX",
            "NUL",
            "COM1",
            "COM2",
            "COM3",
            "COM4",
            "COM5",
            "COM6",
            "COM7",
            "COM8",
            "COM9",
            "LPT1",
            "LPT2",
            "LPT3",
            "LPT4",
            "LPT5",
            "LPT6",
            "LPT7",
            "LPT8",
            "LPT9");

    private static final Map<String, String> EXTENSIONS = Map.ofEntries(
            Map.entry(".txt", "text/plain"),
            Map.entry(".log", "text/plain"),
            Map.entry(".md", "text/markdown"),
            Map.entry(".csv", "text/csv"),
            Map.entry(".tsv", "text/tab-separated-values"),
            Map.entry(".json", "application/json"),
            Map.entry(".xml", "application/xml"),
            Map.entry(".yaml", "application/yaml"),
            Map.entry(".yml", "application/yaml"),
            Map.entry(".rtf", "application/rtf"),
            Map.entry(".html", "text/html"),
            Map.entry(".htm", "text/html"),
            Map.entry(".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry(".xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry(".xls", "application/vnd.ms-excel"),
            Map.entry(".pdf", "application/pdf"),
            Map.entry(".png", "image/png"),
            Map.entry(".jpg", "image/jpeg"),
            Map.entry(".jpeg", "image/jpeg"));

    static final String SUPPORTED = "TXT, LOG, MD, CSV, TSV, JSON, XML, YAML, RTF, HTML, DOCX, XLSX, XLS, PDF, PNG and JPEG";

    private UploadTypes() {
    }

    /** Rejects the whole batch with an actionable message; nothing is stored when any file is invalid. */
    static void validate(List<UploadedFile> uploads, UploadProperties limits) {
        if (uploads.isEmpty()) {
            return;
        }
        if (!limits.enabled()) {
            throw new IllegalArgumentException("File uploads are disabled");
        }
        if (uploads.size() > limits.maxFiles()) {
            throw new IllegalArgumentException("Attach at most " + limits.maxFiles() + " files");
        }
        long total = 0;
        Set<String> names = new HashSet<>();
        for (UploadedFile upload : uploads) {
            String filename = filename(upload.filename());
            if (!names.add(filename.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Duplicate upload filename: " + filename);
            }
            if (extension(filename) == null) {
                throw new IllegalArgumentException(
                        "Unsupported file type for " + filename + "; supported types are " + SUPPORTED);
            }
            long size = upload.bytes().length;
            if (size == 0) {
                throw new IllegalArgumentException("Empty file: " + filename);
            }
            if (size > limits.maxFileBytes()) {
                throw new IllegalArgumentException(
                        filename + " exceeds the per-file limit of " + limits.maxFileBytes() + " bytes");
            }
            total += size;
            if (total > limits.maxTotalBytes()) {
                throw new IllegalArgumentException(
                        "Uploads exceed the total limit of " + limits.maxTotalBytes() + " bytes");
            }
        }
    }

    static String filename(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}") || value.endsWith(".")
                || value.contains("..")
                || RESERVED.contains(value.split("\\.", 2)[0].toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException(
                    "Use a simple non-reserved filename of at most 100 ASCII letters, digits, dots, dashes or underscores");
        }
        return value;
    }

    /** The media type recorded for the stored bytes: signatures first, then the extension. */
    static String mediaType(String filename, byte[] bytes) {
        if (DocumentExtractor.pdf(bytes)) {
            return "application/pdf";
        }
        if (starts(bytes, 0x89, 'P', 'N', 'G')) {
            return "image/png";
        }
        if (starts(bytes, 0xff, 0xd8, 0xff)) {
            return "image/jpeg";
        }
        String extension = extension(filename);
        return extension == null ? "application/octet-stream" : EXTENSIONS.get(extension);
    }

    private static String extension(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        for (String extension : EXTENSIONS.keySet()) {
            if (lower.endsWith(extension)) {
                return extension;
            }
        }
        return null;
    }

    private static boolean starts(byte[] bytes, int... signature) {
        if (bytes.length < signature.length) {
            return false;
        }
        for (int index = 0; index < signature.length; index++) {
            if ((bytes[index] & 0xff) != signature[index]) {
                return false;
            }
        }
        return true;
    }
}
