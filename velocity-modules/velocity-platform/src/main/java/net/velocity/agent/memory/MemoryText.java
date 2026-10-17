package net.velocity.agent.memory;

import java.util.Locale;
import java.util.regex.Pattern;

import net.velocity.agent.text.StoredTextSafety;

/** Defense in depth only. Persisted text is always contextual data, never authority. */
public final class MemoryText {

    private static final Pattern AUTHORITY = Pattern.compile(
            "(?i)(?:ignore|override|bypass|disable|disregard)\\s+(?:(?:all|any|the|previous|prior|system|developer|trusted|safety|security|tool)\\s+){0,4}(?:instructions?|rules?|authorization|permissions?|safeguards?|checks?|restrictions?)|(?:grant|give)\\s+(?:me|yourself|the agent)\\s+(?:all |admin(?:istrator)? )?(?:access|permissions?)");

    private MemoryText() {
    }

    public static String validate(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max || !value.equals(value.strip())) {
            throw new IllegalArgumentException(
                    "Content must be nonblank, trimmed, and within its character limit");
        }
        switch (StoredTextSafety.unicodeIssue(value, false)) {
            case UNPAIRED_HIGH_SURROGATE ->
                throw new IllegalArgumentException("Content must contain valid Unicode");
            case UNSUPPORTED_CHARACTER ->
                throw new IllegalArgumentException("Content contains unsupported characters");
            case NONE -> {
            }
        }
        if (StoredTextSafety.containsRecognizableSecret(value) || AUTHORITY.matcher(value).find()
                || value.toLowerCase(Locale.ROOT).contains("</saved_facts>")) {
            throw new IllegalArgumentException("Content failed memory safety validation");
        }
        return value;
    }
}
