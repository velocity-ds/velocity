package net.velocity.agent.slack;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.slack.api.methods.MethodsClient;
import com.slack.api.socket_mode.SocketModeClient;
import com.slack.api.socket_mode.response.AckResponse;

import net.velocity.agent.persistence.ConflictException;

/**
 * Socket Mode sends the acknowledgement before enqueueing domain work,
 * including with an inline executor.
 */
public final class SlackSocketIngress {

    private static final Logger log = LoggerFactory.getLogger(SlackSocketIngress.class);
    private final SlackIngressGateway ingress;
    private final MethodsClient client;
    private final Executor executor;

    public SlackSocketIngress(SlackIngressGateway ingress, MethodsClient client, Executor executor) {
        this.ingress = Objects.requireNonNull(ingress);
        this.client = Objects.requireNonNull(client);
        this.executor = Objects.requireNonNull(executor);
    }

    public void install(SocketModeClient socket) {
        socket.addEventsApiEnvelopeListener(envelope -> {
            socket.sendSocketModeResponse(new AckResponse(envelope.getEnvelopeId()));
            dispatch(() -> event(envelope.getPayload()));
        });
        socket.addInteractiveEnvelopeListener(envelope -> {
            socket.sendSocketModeResponse(new AckResponse(envelope.getEnvelopeId()));
            dispatch(() -> interaction(envelope.getPayload()));
        });
    }

    private void dispatch(Runnable operation) {
        try {
            executor.execute(() -> {
                try {
                    operation.run();
                } catch (RuntimeException failure) {
                    log.warn(
                            "stage=slack_ingress outcome=rejected exception={}",
                            failure.getClass().getSimpleName());
                }
            });
        } catch (RuntimeException failure) {
            log.warn(
                    "stage=slack_ingress outcome=queue_rejected exception={}",
                    failure.getClass().getSimpleName());
        }
    }

    private void event(JsonElement payload) {
        JsonObject body = object(payload);
        JsonObject event = object(body.get("event"));
        String type = string(event, "type");
        String subtype = string(event, "subtype");
        // A human message with attached files arrives as subtype file_share; every other subtype is ignored.
        boolean fileShare = "message".equals(type) && "file_share".equals(subtype);
        if (!("app_mention".equals(type) || "message".equals(type)) || event.has("subtype") && !fileShare
                || event.has("bot_id") || event.has("bot_profile") || event.has("edited")) {
            return;
        }
        boolean allowCreate = "app_mention".equals(type) || "im".equals(string(event, "channel_type"));
        if (!allowCreate && string(event, "thread_ts") == null) {
            return;
        }
        var identity = identity();
        if (!identity.team().equals(string(body, "team_id"))) {
            throw new SecurityException("Slack workspace mismatch");
        }
        List<SlackFile> files = files(event);
        String text = string(event, "text");
        if (text == null) {
            if (files.isEmpty()) {
                return;
            }
            text = "";
        }
        // A subscribed channel message may precede its app_mention event. Normalize
        // both identically so durable deduplication cannot turn a control into steering.
        text = text.replaceAll("<@" + Pattern.quote(identity.bot()) + "(?:\\|[^>]+)?>", "").strip();
        var message = new SlackIngressGateway.Message(
                identity.team(),
                string(event, "channel"),
                string(event, "user"),
                identity.bot(),
                string(event, "thread_ts"),
                string(event, "ts"),
                text,
                false,
                files);
        try {
            ingress.message(message, allowCreate);
        } catch (SlackHistoryException failure) {
            reportHistoryFailure(message, failure);
        } catch (SlackFileException failure) {
            log.warn("stage=slack_files outcome=rejected code={}", failure.code());
            reportNotice(
                    message,
                    "I couldn't take the file(s) attached to this message, so it was not applied to a task. "
                            + failure.notice());
        } catch (IllegalArgumentException rejected) {
            if (files.isEmpty()) {
                throw rejected;
            }
            // Storage rejections carry requester-actionable text (type, size and name rules), never payloads.
            log.warn("stage=slack_files outcome=rejected code=storage_rejected");
            reportNotice(
                    message,
                    "I couldn't attach the file(s) from this message, so it was not applied to a task. "
                            + rejected.getMessage());
        } catch (ConflictException changed) {
            log.warn("stage=slack_ingress outcome=task_changed");
            reportNotice(
                    message,
                    "The task changed while this message was being processed, so the message was not applied. "
                            + "Please mention me again or send a new direct message to continue.");
        }
    }

    private void reportHistoryFailure(SlackIngressGateway.Message message, SlackHistoryException failure) {
        log.warn(
                "stage=slack_history outcome=rejected category={} error={} retryAfterSeconds={}",
                failure.category(),
                failure.errorCode(),
                failure.retryAfterSeconds());
        String guidance = switch (failure.category()) {
            case RATE_LIMITED -> failure.retryAfterSeconds() == null
                    ? "Slack rate-limited thread retrieval. Please retry later."
                    : "Slack rate-limited thread retrieval. Please retry after " + failure.retryAfterSeconds()
                            + " seconds.";
            case PERMISSION_DENIED ->
                "Check the app's history scopes and conversation access, reinstall if scopes changed, then retry.";
            default -> "Please retry once Slack thread history is available.";
        };
        String notice = "I couldn't retrieve the complete Slack thread, so this message was not applied to a task. "
                + guidance + " (" + failure.errorCode() + ")";
        reportNotice(message, notice);
    }

    private void reportNotice(SlackIngressGateway.Message message, String notice) {
        String thread = message.threadId() == null || message.threadId().isBlank()
                ? message.timestamp()
                : message.threadId();
        try {
            var response = client.chatPostMessage(
                    request -> request.channel(message.channelId()).threadTs(thread)
                            .text(notice).unfurlLinks(false).unfurlMedia(false));
            if (response == null || !response.isOk()) {
                log.warn("stage=slack_ingress outcome=notice_failed");
            }
        } catch (Exception rejected) {
            // Do not retry an unacknowledged write or log SDK payloads/credentials.
            log.warn(
                    "stage=slack_ingress outcome=notice_failed exception={}",
                    rejected.getClass().getSimpleName());
        }
    }

    private void interaction(JsonElement payload) {
        JsonObject body = object(payload);
        if (!"block_actions".equals(string(body, "type"))) {
            return;
        }
        var identity = identity();
        if (!identity.team().equals(string(object(body.get("team")), "id"))) {
            throw new SecurityException("Slack workspace mismatch");
        }
        JsonObject container = object(body.get("container"));
        JsonObject message = object(body.get("message"));
        String channel = string(container, "channel_id");
        if (channel == null) {
            channel = string(object(body.get("channel")), "id");
        }
        String thread = string(message, "thread_ts");
        if (thread == null) {
            thread = string(container, "thread_ts");
        }
        if (thread == null) {
            throw new IllegalArgumentException("Slack control missing thread identity");
        }
        var actions = body.getAsJsonArray("actions");
        if (actions == null || actions.size() != 1) {
            throw new IllegalArgumentException("Invalid Slack controls");
        }
        JsonObject action = object(actions.get(0));
        ingress.control(
                new SlackIngressGateway.Control(
                        identity.team(),
                        channel,
                        string(object(body.get("user")), "id"),
                        identity.bot(),
                        thread,
                        string(action, "action_id"),
                        string(action, "value")));
    }

    private Identity identity() {
        try {
            var auth = client.authTest(request -> request);
            if (!auth.isOk() || auth.getTeamId() == null || auth.getUserId() == null) {
                throw new SecurityException("Cannot establish Slack app identity");
            }
            return new Identity(auth.getTeamId(), auth.getUserId());
        } catch (Exception failure) {
            throw new SecurityException("Cannot establish Slack app identity");
        }
    }

    private record Identity(String team, String bot) {}

    private static JsonObject object(JsonElement value) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException("Invalid Slack payload");
        }
        return value.getAsJsonObject();
    }

    /** The event's file entries as untrusted metadata; nothing is downloaded here. */
    private static List<SlackFile> files(JsonObject event) {
        JsonElement value = event.get("files");
        if (value == null || value.isJsonNull()) {
            return List.of();
        }
        if (!value.isJsonArray()) {
            throw new IllegalArgumentException("Invalid Slack payload");
        }
        JsonArray entries = value.getAsJsonArray();
        var files = new ArrayList<SlackFile>();
        for (JsonElement entry : entries) {
            JsonObject file = object(entry);
            String url = string(file, "url_private_download");
            if (url == null) {
                url = string(file, "url_private");
            }
            files.add(
                    new SlackFile(
                            string(file, "id"),
                            string(file, "name"),
                            string(file, "title"),
                            string(file, "filetype"),
                            string(file, "mimetype"),
                            number(file, "size"),
                            url,
                            string(file, "mode")));
        }
        return List.copyOf(files);
    }

    private static String string(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString()
                : null;
    }

    private static Long number(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        try {
            return value.getAsLong();
        } catch (NumberFormatException invalid) {
            return null;
        }
    }
}
