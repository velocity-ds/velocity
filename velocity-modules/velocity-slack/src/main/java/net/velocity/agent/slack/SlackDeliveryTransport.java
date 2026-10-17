package net.velocity.agent.slack;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.SlackApiTextResponse;
import com.slack.api.methods.response.chat.ChatAppendStreamResponse;
import com.slack.api.methods.response.chat.ChatStartStreamResponse;

import net.velocity.agent.delivery.DeliveryTransport;
import net.velocity.agent.research.ResearchService;

/** Slack streaming operations without automatic write retries or fallback replacement posts. */
public final class SlackDeliveryTransport implements DeliveryTransport {

    private static final Set<String> PAYLOAD_REJECTIONS = Set.of(
            "invalid_chunks",
            "invalid_arguments",
            "invalid_arg_name",
            "invalid_array_arg",
            "invalid_blocks",
            "invalid_blocks_format",
            "invalid_form_data",
            "cannot_provide_both_markdown_text_and_chunks",
            "markdown_text_or_chunks_required",
            "markdown_text_required",
            "msg_too_long",
            "msg_blocks_too_long",
            "msg_blocks_too_many",
            "no_text",
            "rate_limited",
            "ratelimited");
    private static final Set<String> UNUSABLE_STREAM = Set.of(
            "message_not_found",
            "message_not_in_streaming_state",
            "message_not_owned_by_app",
            "stopped_by_user",
            "channel_not_found",
            "is_archived",
            "not_in_channel",
            "access_denied",
            "invalid_auth",
            "account_inactive",
            "token_expired",
            "token_revoked",
            "missing_scope",
            "no_permission",
            "not_authed");
    private final MethodsClient client;
    private final SlackAccessVerifier access;
    private final String botToken;
    private final ResearchService research;

    @FunctionalInterface
    interface ByteUpload {

        void upload(String url, byte[] bytes) throws Exception;
    }

    private final ByteUpload byteUpload;
    private static final okhttp3.OkHttpClient UPLOAD_HTTP = new okhttp3.OkHttpClient.Builder()
            .connectTimeout(java.time.Duration.ofSeconds(10)).readTimeout(java.time.Duration.ofSeconds(20))
            .writeTimeout(java.time.Duration.ofSeconds(20)).callTimeout(java.time.Duration.ofSeconds(30))
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build();

    public SlackDeliveryTransport(MethodsClient client, SlackAccessVerifier access, String botToken) {
        this(client, access, botToken, null);
    }

    public SlackDeliveryTransport(
            MethodsClient client,
            SlackAccessVerifier access,
            String botToken,
            ResearchService research) {
        this(client, access, botToken, research, SlackDeliveryTransport::uploadBytes);
    }

    SlackDeliveryTransport(
            MethodsClient client,
            SlackAccessVerifier access,
            String botToken,
            ResearchService research,
            ByteUpload byteUpload) {
        this.client = Objects.requireNonNull(client);
        this.access = Objects.requireNonNull(access);
        this.botToken = Objects.requireNonNull(botToken);
        this.research = research;
        this.byteUpload = byteUpload;
    }

    @Override
    public Receipt execute(Operation operation) throws Exception {
        var destination = operation.destination();
        access.verify(destination.scope());
        String channel = destination.scope().channelId();
        return switch (operation.kind()) {
            case "file_prepare" -> {
                var report = report(operation);
                var response = client.filesGetUploadURLExternal(
                        request -> request.filename(report.filename())
                                .length(Math.toIntExact(report.byteSize())));
                require(response);
                validateUploadUrl(response.getUploadUrl());
                yield new Receipt(operation.id(), response.getFileId(), response.getUploadUrl());
            }
            case "file_upload" -> {
                var report = report(operation);
                validateUploadUrl(operation.file().uploadUrl());
                byte[] bytes = research.adminDownload(operation.executionId(), report.id())
                        .orElseThrow(() -> new IllegalStateException("Saved report is unavailable"));
                String checksum = java.util.HexFormat.of()
                        .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
                if (bytes.length != report.byteSize() || !checksum.equals(report.checksum())) {
                    throw new IllegalStateException("Report integrity mismatch");
                }
                byteUpload.upload(operation.file().uploadUrl(), bytes);
                yield new Receipt(operation.id(), operation.remoteId());
            }
            case "file_complete" -> {
                var report = report(operation);
                var file = com.slack.api.methods.request.files.FilesCompleteUploadExternalRequest.FileDetails
                        .builder()
                        .id(operation.remoteId()).title(report.filename()).build();
                var response = client.filesCompleteUploadExternal(
                        request -> request.files(java.util.List.of(file))
                                .channelId(channel).threadTs(destination.threadId()));
                require(response);
                if (response.getFiles() == null || response.getFiles().stream()
                        .noneMatch(value -> operation.remoteId().equals(value.getId()))) {
                    throw new Failure("Missing Slack file acknowledgement", false, false);
                }
                yield new Receipt(operation.id(), operation.remoteId());
            }
            case "start" -> {
                // The pinned SDK does not model chunks or task_display_mode yet. Its authenticated HTTP
                // entry point retains the same configured client, timeouts, response parsing and no retries.
                var response = operation.chunks() == null
                        ? client.chatStartStream(
                                request -> request.channel(channel)
                                        .threadTs(destination.threadId())
                                        .recipientUserId(destination.scope().requesterId())
                                        .recipientTeamId(destination.scope().workspaceId())
                                        .markdownText(operation.text()))
                        : client.postFormWithTokenAndParseResponse(
                                form -> form.add("channel", channel)
                                        .add("thread_ts", destination.threadId())
                                        .add("recipient_user_id", destination.scope().requesterId())
                                        .add("recipient_team_id", destination.scope().workspaceId())
                                        .add("task_display_mode", "plan").add("chunks", operation.chunks()),
                                "chat.startStream",
                                botToken,
                                ChatStartStreamResponse.class);
                require(response);
                yield new Receipt(operation.id(), response.getTs());
            }
            case "progress", "answer" -> {
                var response = operation.chunks() == null
                        ? client.chatAppendStream(
                                request -> request.channel(channel)
                                        .ts(operation.remoteId()).markdownText(operation.text()))
                        : client.postFormWithTokenAndParseResponse(
                                form -> form.add("channel", channel)
                                        .add("ts", operation.remoteId()).add("chunks", operation.chunks()),
                                "chat.appendStream",
                                botToken,
                                ChatAppendStreamResponse.class);
                require(response);
                yield new Receipt(operation.id(), operation.remoteId());
            }
            case "stop" -> {
                var response = client.chatStopStream(
                        request -> request.channel(channel).ts(operation.remoteId())
                                .blocksAsString(operation.blocks()));
                require(response);
                yield new Receipt(operation.id(), operation.remoteId());
            }
            case "update" -> {
                if (operation.remoteId() == null || operation.remoteId().isBlank()) {
                    throw new IllegalArgumentException("Missing Slack update destination");
                }
                var metadata = new com.slack.api.model.Message.Metadata();
                metadata.setEventType("velocity_agent_delivery");
                metadata.setEventPayload(Map.of("operationId", operation.id()));
                String text = operation.text();
                String fallback = text != null && text.codePointCount(0, text.length()) <= 4_000
                        ? text
                        : "Response available in the message below.";
                var response = client.chatUpdate(
                        request -> request.channel(channel).ts(operation.remoteId())
                                .text(fallback).blocksAsString(operation.blocks()).metadata(metadata));
                require(response);
                if (!operation.remoteId().equals(response.getTs())
                        || !channel.equals(response.getChannel())) {
                    throw new Failure(
                            "Missing matching Slack update acknowledgement",
                            false,
                            false,
                            "invalid_acknowledgement");
                }
                yield new Receipt(operation.id(), operation.remoteId());
            }
            case "post", "terminal_notice" -> {
                var metadata = new com.slack.api.model.Message.Metadata();
                metadata.setEventType("velocity_agent_delivery");
                metadata.setEventPayload(postMetadata(operation));
                com.slack.api.methods.response.chat.ChatPostMessageResponse response;
                try {
                    response = client.chatPostMessage(
                            request -> request.channel(channel).threadTs(destination.threadId())
                                    .text(operation.text()).blocksAsString(operation.blocks())
                                    .metadata(metadata)
                                    .unfurlLinks(false).unfurlMedia(false));
                } catch (SlackApiException failure) {
                    if ("terminal_notice".equals(operation.kind()) && failure.getResponse() != null
                            && failure.getResponse().code() == 429) {
                        throw new Failure(
                                "Slack notice rate limited",
                                true,
                                true,
                                "ratelimited",
                                retryAfterMillis(failure.getResponse().header("Retry-After")));
                    }
                    throw failure;
                }
                require(response);
                if ("terminal_notice".equals(operation.kind()) && !channel.equals(response.getChannel())) {
                    throw new Failure(
                            "Missing matching Slack notice acknowledgement",
                            false,
                            false,
                            "invalid_acknowledgement");
                }
                yield new Receipt(operation.id(), response.getTs());
            }
            default -> throw new IllegalArgumentException("Unsupported delivery operation");
        };
    }

    private static Map<String, Object> postMetadata(Operation operation) {
        if (!"terminal_notice".equals(operation.kind())) {
            return Map.of("operationId", operation.id());
        }
        return Map.of(
                "operationId",
                operation.id(),
                "executionId",
                operation.executionId(),
                "deliveryTurn",
                Long.toString(operation.turn()),
                "kind",
                operation.kind(),
                "bindingDigest",
                operation.answerDigest());
    }

    private static long retryAfterMillis(String value) {
        if (value == null) {
            return 0;
        }
        try {
            long seconds = Long.parseLong(value.strip());
            if (seconds <= 0) {
                return 0;
            }
            return seconds > Long.MAX_VALUE / 1_000 ? Long.MAX_VALUE : seconds * 1_000;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static void uploadBytes(String url, byte[] bytes) throws Exception {
        // This client sends no Slack bearer token to the signed upload URL and follows no redirects.
        var request = new okhttp3.Request.Builder().url(url)
                .post(okhttp3.RequestBody.create(bytes, okhttp3.MediaType.parse("application/octet-stream")))
                .build();
        try (var response = UPLOAD_HTTP.newCall(request).execute()) {
            if (response.code() != 200) {
                throw new Failure("Slack did not acknowledge report bytes", false, false);
            }
        }
    }

    private net.velocity.agent.research.ResearchArtifact report(Operation operation) {
        if (research == null || operation.file() == null || operation.file().report() == null) {
            throw new IllegalArgumentException("Report delivery is not configured");
        }
        var selected = operation.file().report();
        var report = research
                .ownedDeliverable(
                        operation.executionId(),
                        operation.destination().scope(),
                        selected.reportId())
                .orElseThrow(
                        () -> new IllegalArgumentException(
                                "Report does not belong to this task destination"));
        if (!report.checksum().equals(selected.checksum()) || report.byteSize() != selected.byteSize()
                || report.goalGeneration() != selected.goalGeneration()
                || !report.filename().equals(selected.filename())
                || !report.mediaType().equals(selected.mediaType())) {
            throw new IllegalArgumentException("Report manifest mismatch");
        }
        return report;
    }

    private static void validateUploadUrl(String value) {
        var uri = java.net.URI.create(Objects.requireNonNull(value));
        if (!"https".equals(uri.getScheme()) || !"files.slack.com".equals(uri.getHost())
                || uri.getRawUserInfo() != null || uri.getFragment() != null || uri.getPort() != -1
                || !uri.getPath().startsWith("/upload/")) {
            throw new IllegalArgumentException("Untrusted Slack upload destination");
        }
    }

    @Override
    public Optional<Receipt> inspect(Operation operation) throws Exception {
        // Streams have no unique operation metadata or authoritative append/stop receipt. A matching
        // plan title, bot name or text prefix is not positive evidence of this specific stream creation.
        boolean update = "update".equals(operation.kind());
        boolean terminalNotice = "terminal_notice".equals(operation.kind());
        if (!"post".equals(operation.kind()) && !update && !terminalNotice) {
            return Optional.empty();
        }
        access.verify(operation.destination().scope());
        java.util.List<com.slack.api.model.Message> messages;
        if (operation.destination().threadId() == null) {
            var response = client.conversationsHistory(
                    request -> request
                            .channel(operation.destination().scope().channelId()).limit(100)
                            .includeAllMetadata(true));
            if (!response.isOk() || response.getMessages() == null) {
                return Optional.empty();
            }
            messages = response.getMessages();
        } else {
            var response = client.conversationsReplies(
                    request -> request
                            .channel(operation.destination().scope().channelId())
                            .ts(operation.destination().threadId())
                            .limit(100).includeAllMetadata(true));
            if (!response.isOk() || response.getMessages() == null) {
                return Optional.empty();
            }
            messages = response.getMessages();
        }
        for (var message : messages.stream().limit(100).toList()) {
            if (!operation.destination().scope().botId().equals(message.getUser())) {
                continue;
            }
            if (update && (operation.remoteId() == null || !operation.remoteId().equals(message.getTs())
                    || !Objects.equals(operation.destination().threadId(), message.getThreadTs()))) {
                continue;
            }
            if (terminalNotice && (!Objects.equals(operation.destination().threadId(), message.getThreadTs())
                    || message.getTs() == null || message.getTs().isBlank())) {
                continue;
            }
            var metadata = message.getMetadata();
            boolean match = metadata != null && "velocity_agent_delivery".equals(metadata.getEventType())
                    && metadata.getEventPayload() != null
                    && operation.id().equals(metadata.getEventPayload().get("operationId"));
            if (match && (!terminalNotice || postMetadata(operation).equals(metadata.getEventPayload()))) {
                return Optional.of(new Receipt(operation.id(), message.getTs()));
            }
        }
        return Optional.empty();
    }

    private static void require(SlackApiTextResponse response) throws Failure {
        if (response == null) {
            throw new Failure("Missing Slack acknowledgement", false, false, "missing_acknowledgement");
        }
        if (response.isOk()) {
            return;
        }
        String error = response.getError();
        boolean usable = PAYLOAD_REJECTIONS.contains(Objects.toString(error, ""));
        boolean absent = usable || UNUSABLE_STREAM.contains(Objects.toString(error, ""));
        // internal_error/fatal_error explicitly allow a partially applied operation in Slack's contract.
        // Unknown errors also remain uncertain; never infer non-delivery merely from ok=false.
        throw new Failure("Slack did not acknowledge delivery", absent, usable, error);
    }
}
