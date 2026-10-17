package net.velocity.agent.slack;

import java.util.Objects;
import java.util.Set;

/**
 * A safe, explicit failure to accept the files attached to a Slack message. The code is one of a fixed set
 * for logs; the notice is built from fixed text and a sanitized filename, so it can be posted to the thread.
 */
public final class SlackFileException extends RuntimeException {

    private static final Set<String> SAFE_CODES = Set.of(
            "uploads_disabled",
            "too_many_files",
            "file_too_large",
            "total_too_large",
            "unavailable",
            "unsupported_source",
            "download_denied",
            "download_failed",
            "empty_file",
            "transport_error",
            "unknown_error");

    private final String code;
    private final String notice;

    public SlackFileException(String code, String notice) {
        super(notice);
        this.code = SAFE_CODES.contains(code) ? code : "unknown_error";
        this.notice = Objects.requireNonNull(notice);
    }

    /** A fixed, loggable code; never a URL, token or payload fragment. */
    public String code() {
        return code;
    }

    /** Requester-facing guidance safe to post in the originating thread. */
    public String notice() {
        return notice;
    }
}
