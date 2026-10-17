package net.velocity.agent.text;

import java.util.regex.Pattern;

/** Recognizable-secret and Unicode checks, not a complete security boundary or an authority filter. */
public final class StoredTextSafety {

    // Union of the established fact/skill families, using the more conservative existing thresholds.
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(?:sk-[a-z0-9_-]{12,}|xox[baprs]-[a-z0-9-]{8,}|AKIA[A-Z0-9]{16}|"
                    + "-----BEGIN [A-Z ]*PRIVATE KEY|"
                    + "(?:password|api[_ -]?key|access[_ -]?token|secret)\\s*[:=]\\s*\\S{6,}|"
                    + "authorization\\s*[:=]\\s*\\S{8,}|Bearer\\s+[a-z0-9._~-]{12,})");

    private StoredTextSafety() {
    }

    public static boolean containsRecognizableSecret(String value) {
        return SECRET.matcher(value).find();
    }

    public static UnicodeIssue unicodeIssue(String value, boolean allowCarriageReturn) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    return UnicodeIssue.UNPAIRED_HIGH_SURROGATE;
                }
            } else if (Character.isLowSurrogate(c) || Character.isISOControl(c)
                    && c != '\n' && c != '\t' && !(allowCarriageReturn && c == '\r')) {
                return UnicodeIssue.UNSUPPORTED_CHARACTER;
            }
        }
        return UnicodeIssue.NONE;
    }

    public enum UnicodeIssue {
        NONE, UNPAIRED_HIGH_SURROGATE, UNSUPPORTED_CHARACTER
    }
}
