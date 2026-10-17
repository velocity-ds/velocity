package net.velocity.agent.codeexecution;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.velocity.util.Sha256;

/** Portable, single-component filenames and bounded supported artifact formats. */
public final class CodeFiles {

    private CodeFiles() {
    }

    private static final Set<String> RESERVED = Set.of(
            "CON",
            "PRN",
            "AUX",
            "NUL",
            "COM0",
            "COM1",
            "COM2",
            "COM3",
            "COM4",
            "COM5",
            "COM6",
            "COM7",
            "COM8",
            "COM9",
            "LPT0",
            "LPT1",
            "LPT2",
            "LPT3",
            "LPT4",
            "LPT5",
            "LPT6",
            "LPT7",
            "LPT8",
            "LPT9");

    public static String filename(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}") || value.endsWith(".")
                || value.contains("..")
                || RESERVED.contains(value.split("\\.", 2)[0].toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException(
                    "Use a simple non-reserved filename of at most 100 ASCII characters");
        }
        return value;
    }

    public static void unique(List<String> filenames) {
        Set<String> names = new HashSet<>();
        for (String name : filenames) {
            if (!names.add(filename(name).toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Duplicate code file destination");
            }
        }
    }

    public static String mediaType(String filename, byte[] bytes) {
        filename(filename);
        String suffix = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return switch (suffix) {
            case "csv" -> "text/csv";
            case "tsv" -> "text/tab-separated-values";
            case "txt", "log" -> "text/plain";
            case "md" -> "text/markdown";
            case "json" -> "application/json";
            case "png" -> {
                if (bytes.length < 8 || !java.util.Arrays.equals(
                        java.util.Arrays.copyOf(bytes, 8),
                        new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10})) {
                    throw new IllegalArgumentException("Invalid PNG output");
                }
                yield "image/png";
            }
            case "jpg", "jpeg" -> {
                if (bytes.length < 3 || bytes[0] != (byte) 0xff || bytes[1] != (byte) 0xd8
                        || bytes[2] != (byte) 0xff) {
                    throw new IllegalArgumentException("Invalid JPEG output");
                }
                yield "image/jpeg";
            }
            case "xlsx" -> {
                if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K' || bytes[2] != 3
                        || bytes[3] != 4) {
                    throw new IllegalArgumentException("Invalid spreadsheet output");
                }
                yield "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            }
            case "pdf" -> {
                if (bytes.length < 5 || !new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) {
                    throw new IllegalArgumentException("Invalid PDF output");
                }
                yield "application/pdf";
            }
            default -> throw new IllegalArgumentException("Unsupported code output format");
        };
    }

    public static String hash(byte[] bytes) {
        return Sha256.hex(bytes);
    }
}
