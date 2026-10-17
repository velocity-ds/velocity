package net.velocity.agent.slack;

import java.util.Set;

/** A safe, explicit failure to retrieve complete conversational history from Slack. */
public final class SlackHistoryException extends RuntimeException {

    public enum Category {
        RATE_LIMITED, PERMISSION_DENIED, API_FAILURE, INCOMPLETE_HISTORY
    }

    private static final Set<String> SAFE_CODES = Set.of(
            "rate_limited",
            "ratelimited",
            "missing_scope",
            "not_allowed_token_type",
            "no_permission",
            "access_denied",
            "not_in_channel",
            "channel_not_found",
            "invalid_auth",
            "not_authed",
            "account_inactive",
            "token_expired",
            "token_revoked",
            "org_login_required",
            "thread_not_found",
            "message_not_found",
            "invalid_arguments",
            "invalid_arg_name",
            "invalid_ts_latest",
            "invalid_ts_oldest",
            "internal_error",
            "fatal_error",
            "service_unavailable",
            "request_timeout",
            "http_error",
            "transport_error",
            "missing_response",
            "empty_page",
            "malformed_message",
            "malformed_timestamp",
            "missing_parent",
            "missing_cursor",
            "repeated_cursor",
            "unknown_error");

    private final Category category;
    private final String errorCode;
    private final Long retryAfterSeconds;

    public SlackHistoryException(Category category, String errorCode, Long retryAfterSeconds) {
        super(message(category, safeCode(errorCode), retryAfterSeconds));
        this.category = java.util.Objects.requireNonNull(category);
        this.errorCode = safeCode(errorCode);
        this.retryAfterSeconds = retryAfterSeconds != null && retryAfterSeconds >= 0
                ? retryAfterSeconds
                : null;
    }

    public Category category() {
        return category;
    }

    public String errorCode() {
        return errorCode;
    }

    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    private static String safeCode(String value) {
        return value != null && SAFE_CODES.contains(value) ? value : "unknown_error";
    }

    private static String message(Category category, String code, Long retryAfterSeconds) {
        return "Slack thread history is incomplete (" + category + ": " + code + ")."
                + (retryAfterSeconds != null && retryAfterSeconds >= 0
                        ? " Retry after " + retryAfterSeconds + " seconds."
                        : "");
    }
}
