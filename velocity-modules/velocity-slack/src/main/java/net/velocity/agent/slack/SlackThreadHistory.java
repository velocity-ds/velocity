package net.velocity.agent.slack;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.response.conversations.ConversationsRepliesResponse;
import com.slack.api.model.Message;

import net.velocity.agent.slack.SlackHistoryException.Category;
import net.velocity.agent.state.SourceMessage;
import net.velocity.agent.state.SourceTurn;
import net.velocity.agent.state.TrustedScope;

/** Complete thread reads for conversational context, independent of delivery receipt inspection. */
public final class SlackThreadHistory {

    private static final String NON_TEXT = "[Slack message has no text; attachments or other non-text content are not included.]";
    private static final String NON_TEXT_WITH_FILES = "[Slack message has no text; it carried attached files that are not included here: %s]";
    private static final String WITH_FILES = "[This Slack message also carried attached files that are not included here: %s]";
    private static final int MAX_LISTED_FILES = 20;
    private static final Set<String> PERMISSION_ERRORS = Set.of(
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
            "org_login_required");
    private final MethodsClient client;

    public SlackThreadHistory(MethodsClient client) {
        this.client = Objects.requireNonNull(client);
    }

    /**
     * The caller must verify access first. Retrieved text and author labels are untrusted source data;
     * they never supply model roles or instructions. No partial result is returned on failure.
     */
    public List<SourceMessage> fetch(TrustedScope scope, SourceTurn turn, String triggerText) {
        Objects.requireNonNull(scope);
        Objects.requireNonNull(turn);
        Objects.requireNonNull(triggerText);
        if (!"slack".equals(scope.origin()) || !scope.workspaceId().equals(turn.workspaceId())
                || !scope.channelId().equals(turn.channelId())) {
            throw new IllegalArgumentException("Slack history scope does not match the source turn");
        }
        BigDecimal root = timestamp(turn.threadId());
        BigDecimal latest = timestamp(turn.messageId());
        if (root.compareTo(latest) > 0) {
            throw incomplete("malformed_timestamp");
        }

        // BigDecimal retains Slack's microsecond precision and collapses equal timestamps across pages.
        var messages = new TreeMap<BigDecimal, SourceMessage>();
        Set<String> seenCursors = new HashSet<>();
        String cursor = null;
        do {
            ConversationsRepliesResponse response = page(scope.channelId(), turn, cursor);
            if (response.getMessages() == null || response.getMessages().isEmpty()) {
                throw incomplete("empty_page");
            }
            for (Message message : response.getMessages()) {
                if (message == null) {
                    throw incomplete("malformed_message");
                }
                BigDecimal ts = timestamp(message.getTs());
                if (ts.compareTo(latest) > 0) {
                    continue;
                } // Defensive enforcement of the triggering event boundary.
                if (ts.compareTo(root) < 0 || (hasText(message.getChannel())
                        && !scope.channelId().equals(message.getChannel()))
                        || (hasText(message.getThreadTs())
                                && timestamp(message.getThreadTs()).compareTo(root) != 0)) {
                    throw incomplete("malformed_message");
                }
                String botId = hasText(message.getBotId()) ? message.getBotId() : null;
                String author = hasText(message.getUser()) ? message.getUser() : botId;
                if (author == null) {
                    author = "unknown";
                }
                SourceTurn source = new SourceTurn(
                        turn.workspaceId(),
                        turn.channelId(),
                        turn.threadId(),
                        message.getTs());
                messages.put(ts, new SourceMessage(source, author, botId, text(message)));
            }
            String next = response.getResponseMetadata() == null
                    ? null
                    : response.getResponseMetadata().getNextCursor();
            cursor = hasText(next) ? next.strip() : null;
            if (cursor == null && response.isHasMore()) {
                throw incomplete("missing_cursor");
            }
            if (cursor != null && !seenCursors.add(cursor)) {
                throw incomplete("repeated_cursor");
            }
        } while (cursor != null);

        if (!messages.containsKey(root)) {
            throw incomplete("missing_parent");
        }
        // Events can become visible before replies indexing catches up. The trusted event is the exact
        // current user turn; it also replaces its API copy so mention sanitization happens exactly once.
        messages.put(latest, new SourceMessage(turn, scope.requesterId(), null, triggerText));
        return List.copyOf(messages.values());
    }

    private ConversationsRepliesResponse page(String channelId, SourceTurn turn, String cursor) {
        try {
            var response = client.conversationsReplies(
                    request -> request.channel(channelId).ts(turn.threadId())
                            .latest(turn.messageId()).inclusive(true).limit(100).cursor(cursor));
            if (response == null) {
                throw incomplete("missing_response");
            }
            if (!response.isOk()) {
                throw apiFailure(response.getError(), retryAfter(response.getHttpResponseHeaders()));
            }
            return response;
        } catch (SlackHistoryException failure) {
            throw failure;
        } catch (SlackApiException failure) {
            Long retryAfter = failure.getResponse() == null
                    ? null
                    : retryAfter(failure.getResponse().header("Retry-After"));
            if (failure.getResponse() != null && failure.getResponse().code() == 429) {
                throw new SlackHistoryException(Category.RATE_LIMITED, "rate_limited", retryAfter);
            }
            throw apiFailure(
                    failure.getError() == null ? "http_error" : failure.getError().getError(),
                    retryAfter);
        } catch (Exception failure) {
            // SDK exceptions may retain request headers or raw responses. Do not expose them as causes.
            throw new SlackHistoryException(Category.API_FAILURE, "transport_error", null);
        }
    }

    private static SlackHistoryException apiFailure(String error, Long retryAfter) {
        Category category = "rate_limited".equals(error) || "ratelimited".equals(error)
                ? Category.RATE_LIMITED
                : PERMISSION_ERRORS.contains(Objects.toString(error, ""))
                        ? Category.PERMISSION_DENIED
                        : Category.API_FAILURE;
        return new SlackHistoryException(category, error, retryAfter);
    }

    private static BigDecimal timestamp(String value) {
        if (value == null || !value.matches("[0-9]+(?:\\.[0-9]+)?")) {
            throw incomplete("malformed_timestamp");
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException failure) {
            throw incomplete("malformed_timestamp");
        }
    }

    private static SlackHistoryException incomplete(String error) {
        return new SlackHistoryException(Category.INCOMPLETE_HISTORY, error, null);
    }

    /**
     * Earlier turns keep their text; a placeholder names any attached files so the model knows they existed.
     * The files of the triggering message are stored with the task and listed separately.
     */
    private static String text(Message message) {
        String names = fileNames(message.getFiles());
        if (hasText(message.getText())) {
            return names.isEmpty()
                    ? message.getText()
                    : message.getText() + "\n" + WITH_FILES.formatted(names);
        }
        return names.isEmpty() ? NON_TEXT : NON_TEXT_WITH_FILES.formatted(names);
    }

    private static String fileNames(List<com.slack.api.model.File> files) {
        if (files == null || files.isEmpty()) {
            return "";
        }
        var names = new StringBuilder();
        int listed = 0;
        for (com.slack.api.model.File file : files) {
            if (file == null) {
                continue;
            }
            if (listed == MAX_LISTED_FILES) {
                names.append(", and ").append(files.size() - listed).append(" more");
                break;
            }
            String name = hasText(file.getName())
                    ? file.getName()
                    : hasText(file.getTitle())
                            ? file.getTitle()
                            : hasText(file.getId()) ? file.getId() : "unnamed";
            names.append(listed == 0 ? "" : ", ")
                    .append(name.strip(), 0, Math.min(name.strip().length(), 100));
            listed++;
        }
        return names.toString();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static Long retryAfter(Map<String, List<String>> headers) {
        if (headers == null) {
            return null;
        }
        return headers.entrySet().stream().filter(entry -> "Retry-After".equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue).filter(Objects::nonNull).flatMap(List::stream)
                .map(SlackThreadHistory::retryAfter).filter(Objects::nonNull).findFirst().orElse(null);
    }

    private static Long retryAfter(String value) {
        if (value == null || !value.strip().matches("[0-9]+")) {
            return null;
        }
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException failure) {
            return null;
        }
    }
}
