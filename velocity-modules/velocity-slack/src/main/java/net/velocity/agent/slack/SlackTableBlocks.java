package net.velocity.agent.slack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.JsonParser;

/** Converts every complete Markdown pipe table without changing the saved fallback text. */
final class SlackTableBlocks {

    private static final Gson JSON = new Gson();
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");
    private static final Pattern DELIMITER = Pattern.compile(":?-+:?");
    private static final Pattern FILENAME = Pattern.compile("(?i)[^|\\r\\n]+\\.(md|markdown)");

    private SlackTableBlocks() {
    }

    /** Include the full body as blocks so the context footer never hides the fallback answer. */
    static String render(String text, String footer) {
        String rendered = render(text);
        var blocks = new com.google.gson.JsonArray();
        if (rendered != null) {
            blocks.addAll(JsonParser.parseString(rendered).getAsJsonArray());
        }
        if (rendered == null || blocks.size() >= 50) {
            blocks = new com.google.gson.JsonArray();
            var body = new ArrayList<Map<String, Object>>();
            sections(body, text);
            blocks.addAll(JSON.toJsonTree(body).getAsJsonArray());
        }
        blocks.addAll(JsonParser.parseString(footer).getAsJsonArray());
        return JSON.toJson(blocks);
    }

    /**
     * Null retains the original message when a complete, bounded conversion is unavailable: every table
     * in the text must convert, each within Slack's table block limits, or none does.
     */
    static String render(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        List<Line> lines = lines(text);
        var tables = new ArrayList<Table>();
        for (int index = 0; index < lines.size();) {
            var opening = FENCE.matcher(lines.get(index).text());
            if (opening.matches()) {
                String marker = opening.group(1);
                int closing = index + 1;
                while (closing < lines.size() && !closes(lines.get(closing).text(), marker)) {
                    closing++;
                }
                if (closing == lines.size()) {
                    break;
                } // Unclosed code remains literal.
                String language = opening.group(2).strip();
                if (language.isEmpty() || language.equalsIgnoreCase("markdown")
                        || language.equalsIgnoreCase("md")) {
                    Table fenced = fenced(lines, index, closing);
                    if (fenced != null) {
                        tables.add(fenced);
                    } else if (containsTable(lines, index + 1, closing)) {
                        return null;
                    }
                }
                index = closing + 1;
                continue;
            }
            if (index + 1 < lines.size() && separator(lines.get(index + 1).text()) != null
                    && cells(lines.get(index).text()) != null) {
                Table table = table(lines, index, lines.size());
                if (table == null) {
                    return null;
                }
                tables.add(table);
                index = table.lastLine() + 1;
            } else {
                index++;
            }
        }
        if (tables.isEmpty()) {
            return null;
        }
        for (Table table : tables) {
            if (table.rows().size() > 100 || table.alignments().size() > 20) {
                return null;
            }
            int cellCharacters = table.rows().stream().flatMap(List::stream).mapToInt(String::length).sum();
            if (cellCharacters > 10_000) {
                return null;
            }
        }

        var blocks = new ArrayList<Map<String, Object>>();
        int offset = 0;
        for (Table table : tables) {
            sections(blocks, text.substring(offset, table.start()) + table.filename());
            blocks.add(tableBlock(table));
            offset = table.end();
        }
        sections(blocks, text.substring(offset));
        return blocks.size() > 50 ? null : JSON.toJson(blocks);
    }

    private static Map<String, Object> tableBlock(Table table) {
        var rows = table.rows().stream().map(
                row -> row.stream()
                        .map(value -> Map.of("type", "raw_text", "text", value)).toList())
                .toList();
        var settings = table.alignments().stream()
                .map(align -> Map.<String, Object>of("align", align, "is_wrapped", true)).toList();
        return Map.of("type", "table", "rows", rows, "column_settings", settings);
    }

    private static Table fenced(List<Line> lines, int opening, int closing) {
        int first = opening + 1;
        while (first < closing && lines.get(first).text().isBlank()) {
            first++;
        }
        String filename = "";
        if (first < closing && FILENAME.matcher(lines.get(first).text().strip()).matches()) {
            filename = lines.get(first++).text() + "\n";
            while (first < closing && lines.get(first).text().isBlank()) {
                first++;
            }
        }
        Table table = table(lines, first, closing);
        if (table == null) {
            return null;
        }
        for (int index = table.lastLine() + 1; index < closing; index++) {
            if (!lines.get(index).text().isBlank()) {
                return null;
            }
        }
        return new Table(
                lines.get(opening).start(),
                lines.get(closing).end(),
                closing,
                table.rows(),
                table.alignments(),
                filename);
    }

    private static boolean containsTable(List<Line> lines, int first, int limit) {
        for (int index = first; index + 1 < limit; index++) {
            if (cells(lines.get(index).text()) != null && separator(lines.get(index + 1).text()) != null) {
                return true;
            }
        }
        return false;
    }

    private static Table table(List<Line> lines, int first, int limit) {
        if (first + 1 >= limit) {
            return null;
        }
        List<String> header = cells(lines.get(first).text());
        List<String> alignments = separator(lines.get(first + 1).text());
        if (header == null || alignments == null || header.size() != alignments.size()) {
            return null;
        }
        var rows = new ArrayList<List<String>>();
        rows.add(header);
        int index = first + 2;
        while (index < limit && !FENCE.matcher(lines.get(index).text()).matches()) {
            List<String> row = cells(lines.get(index).text());
            if (row == null) {
                if (indented(lines.get(index).text()) && lines.get(index).text().contains("|")) {
                    return null;
                }
                break;
            }
            if (row.size() != header.size() || separator(lines.get(index).text()) != null) {
                return null;
            }
            rows.add(row);
            index++;
        }
        return new Table(
                lines.get(first).start(),
                lines.get(index - 1).end(),
                index - 1,
                rows,
                alignments,
                "");
    }

    private static List<String> separator(String text) {
        List<String> cells = cells(text);
        if (cells == null || cells.stream().anyMatch(value -> !DELIMITER.matcher(value).matches())) {
            return null;
        }
        return cells.stream().map(
                value -> value.endsWith(":")
                        ? value.startsWith(":") ? "center" : "right"
                        : "left")
                .toList();
    }

    private static List<String> cells(String text) {
        if (indented(text)) {
            return null;
        }
        String line = text.strip();
        if (line.isEmpty() || line.indexOf('|') < 0) {
            return null;
        }
        var cells = new ArrayList<String>();
        var cell = new StringBuilder();
        int first = line.charAt(0) == '|' ? 1 : 0;
        boolean trailingSeparator = false;
        for (int index = first; index < line.length(); index++) {
            char value = line.charAt(index);
            if (value == '|') {
                int slashes = 0;
                for (int prior = index - 1; prior >= first && line.charAt(prior) == '\\'; prior--) {
                    slashes++;
                }
                if (slashes % 2 == 1) {
                    cell.setLength(cell.length() - 1);
                    cell.append('|');
                    trailingSeparator = false;
                    continue;
                }
                cells.add(cell.toString().strip());
                cell.setLength(0);
                trailingSeparator = true;
            } else {
                cell.append(value);
                trailingSeparator = false;
            }
        }
        if (!trailingSeparator) {
            cells.add(cell.toString().strip());
        }
        return cells.isEmpty() ? null : cells;
    }

    private static boolean indented(String text) {
        int spaces = 0;
        while (spaces < text.length() && text.charAt(spaces) == ' ') {
            spaces++;
        }
        return spaces >= 4 || spaces < text.length() && text.charAt(spaces) == '\t';
    }

    private static void sections(List<Map<String, Object>> blocks, String text) {
        for (int offset = 0; offset < text.length();) {
            int end = Math.min(offset + 3_000, text.length());
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))
                    && Character.isLowSurrogate(text.charAt(end))) {
                end--;
            }
            String part = text.substring(offset, end);
            if (!part.isBlank()) {
                blocks.add(Map.of("type", "section", "text", Map.of("type", "mrkdwn", "text", part)));
            }
            offset = end;
        }
    }

    private static boolean closes(String text, String marker) {
        var match = FENCE.matcher(text);
        return match.matches() && match.group(1).charAt(0) == marker.charAt(0)
                && match.group(1).length() >= marker.length() && match.group(2).isBlank();
    }

    private static List<Line> lines(String text) {
        var lines = new ArrayList<Line>();
        int offset = 0;
        for (String value : text.split("\n", -1)) {
            lines.add(
                    new Line(
                            value.endsWith("\r") ? value.substring(0, value.length() - 1) : value,
                            offset,
                            offset + value.length() - (value.endsWith("\r") ? 1 : 0)));
            offset += value.length() + 1;
        }
        return lines;
    }

    private record Line(String text, int start, int end) {}

    private record Table(
            int start,
            int end,
            int lastLine,
            List<List<String>> rows,
            List<String> alignments,
            String filename) {}
}
