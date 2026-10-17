package net.velocity.agent.browser;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/** Ephemeral output boundary, deliberately independent of the untrusted renderer. */
final class BrowserContentRedactor {

    private static final Pattern SECRET_KEY = Pattern.compile(
            "(?i).*passw.*|.*secret.*|.*token.*|.*signature.*|.*session.*|authorization|code|state|api.?key");
    private static final Pattern TOKEN = Pattern.compile(
            "(?i)(?:bearer\\s+)[A-Za-z0-9._~+/=-]+|eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+|((?:access_token|id_token|refresh_token|password|secret|authorization|api_key|session_token|authorization_code)\\s*[=:]\\s*)[^\\s&<>\\\"']+");
    private final Set<String> values = new TreeSet<>(
            Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()));
    private final Map<String, Pattern> patterns = new HashMap<>();
    private final Map<String, Pattern> truncatedTokens = new LinkedHashMap<>();
    private final Set<String> requiredValues = new HashSet<>();

    void remember(String value) {
        remember(value, false);
    }

    /** Credential injection cannot lose protection when untrusted pages exhaust the learned-token budget. */
    void rememberRequired(String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Browser credential redaction requires a value");
        }
        // Repeated fills retain the same saved pair within a browser generation.
        if (!requiredValues.contains(value) && requiredValues.size() >= 6) {
            throw new IllegalStateException("Browser credential redaction capacity exceeded");
        }
        requiredValues.add(value);
        remember(value, true);
    }

    private void remember(String value, boolean required) {
        if (value == null || value.isEmpty() || (!required && values.size() >= 512)) {
            return;
        }
        var forms = new HashSet<String>();
        forms.add(value);
        if (!value.strip().isEmpty()) {
            forms.add(value.strip());
        }
        if (!value.isBlank()) {
            forms.add(value.replaceAll("\\s+", " ").strip());
        }
        forms.add(URLEncoder.encode(value, StandardCharsets.UTF_8));
        forms.add(URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"));
        forms.add(
                value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                        .replace("\"", "&quot;"));
        forms.add(Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
        values.addAll(forms);
        for (String item : forms) {
            if (required) {
                patterns.put(item, Pattern.compile(Pattern.quote(item)));
            } else {
                patterns.computeIfAbsent(item, BrowserContentRedactor::occurrence);
            }
        }
        String stripped = value.strip();
        if (stripped.length() >= 16 && (required || truncatedTokens.size() < 512)) {
            truncatedTokens.computeIfAbsent(
                    stripped,
                    item -> Pattern.compile(
                            Pattern.quote(item.substring(0, 12)) + "[^\\s<>]{0," + (item.length() - 12)
                                    + "}"));
        }
    }

    void rememberUrlTokens(String url) {
        try {
            URI uri = URI.create(url);
            for (String part : List.of(
                    Objects.toString(uri.getRawQuery(), ""),
                    Objects.toString(uri.getRawFragment(), ""))) {
                for (String pair : part.split("&")) {
                    int equals = pair.indexOf('=');
                    if (equals > 0 && SECRET_KEY
                            .matcher(URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8))
                            .matches()) {
                        remember(URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
                    }
                }
            }
        } catch (IllegalArgumentException ignored) {
            /* Invalid URLs are rejected by destination validation. */ }
    }

    private static Pattern occurrence(String value) {
        return Pattern.compile(
                value.length() < 8
                        ? "(?<![A-Za-z0-9])" + Pattern.quote(value) + "(?![A-Za-z0-9])"
                        : Pattern.quote(value));
    }

    boolean containsSecret(String text) {
        return text != null && values.stream().anyMatch(value -> patterns.get(value).matcher(text).find());
    }

    String clean(String text) {
        return TOKEN.matcher(cleanKnownValues(text)).replaceAll("[redacted]");
    }

    /** Before SDK logging, preserve unknown source URLs while removing every injected secret. */
    String cleanKnownValues(String text) {
        String safe = text;
        for (String value : values) {
            safe = patterns.get(value).matcher(safe).replaceAll("[redacted]");
        }
        for (Pattern prefix : truncatedTokens.values()) {
            safe = prefix.matcher(safe).replaceAll("[redacted]");
        }
        return safe;
    }

    void clear() {
        values.clear();
        patterns.clear();
        truncatedTokens.clear();
        requiredValues.clear();
    }
}
