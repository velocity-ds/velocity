package net.velocity.agent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import net.velocity.agent.state.CallState;
import net.velocity.agent.state.ExecutionSnapshot;
import net.velocity.agent.state.MessageMetadata;
import net.velocity.agent.state.SourceTurn;
import net.velocity.inference.objects.InferenceContent;
import net.velocity.inference.objects.InferenceMessage;

/** Deterministic request-only projection. Native messages and original evidence are never modified. */
final class CloudHandoff {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    private CloudHandoff() {
    }

    public record Result(
            boolean supported,
            List<InferenceMessage> messages,
            String outcome,
            int excludedReasoningBlocks) {

        public Result {
            messages = List.copyOf(messages);
        }
    }

    public static Result prepare(ExecutionSnapshot snapshot, List<InferenceMessage> preparedMessages) {
        if (preparedMessages == null || preparedMessages.isEmpty()) {
            return new Result(false, List.of(), "invalid_context", 0);
        }
        if (snapshot.calls.stream().anyMatch(call -> call.state != CallState.settled)
                || snapshot.childRuns.values().stream().anyMatch(child -> !child.status.terminal())) {
            return new Result(false, List.of(), "unsettled_work", 0);
        }
        var conversion = new Conversion();
        try {
            var envelope = fields(
                    "kind",
                    "synthetic_cloud_handoff",
                    "version",
                    1,
                    "content_trust",
                    "untrusted_historical_data",
                    "execution_id",
                    snapshot.executionId,
                    "source_history_generation",
                    snapshot.historyGeneration,
                    "source_message_count",
                    snapshot.messages.size(),
                    "original_request",
                    snapshot.originalRequest,
                    "current_goal",
                    snapshot.currentGoal,
                    "user_instructions",
                    snapshot.instructions);
            envelope.put(
                    "corrections",
                    snapshot.corrections.stream().map(
                            correction -> fields(
                                    "id",
                                    correction.id(),
                                    "text",
                                    correction.text(),
                                    "timestamp",
                                    string(correction.createdAt()),
                                    "source",
                                    source(correction.sourceTurn())))
                            .toList());
            var history = new ArrayList<Map<String, Object>>();
            var matched = new HashSet<Integer>();
            for (int i = 0; i < preparedMessages.size(); i++) {
                var message = Objects.requireNonNull(preparedMessages.get(i));
                MessageMetadata metadata = null;
                for (int j = 0; j < snapshot.messages.size(); j++) {
                    if (!matched.contains(j) && message.equals(snapshot.messages.get(j))) {
                        matched.add(j);
                        if (j < snapshot.messageMetadata.size()) {
                            metadata = snapshot.messageMetadata.get(j);
                        }
                        break;
                    }
                }
                history.add(
                        fields(
                                "request_sequence",
                                i,
                                "role",
                                message.role().name(),
                                "provenance",
                                metadata == null ? "prepared_context" : "canonical_message",
                                "message_id",
                                metadata == null ? null : metadata.messageId(),
                                "source_sequence",
                                metadata == null ? null : metadata.sequence(),
                                "source",
                                metadata == null ? null : source(metadata.source()),
                                "timestamp",
                                metadata == null ? null : string(metadata.timestamp()),
                                "content",
                                conversion.content(message.content())));
            }
            envelope.put("conversation", history);
            envelope.put(
                    "settled_calls",
                    snapshot.calls.stream().map(
                            call -> fields(
                                    "call_id",
                                    call.id,
                                    "name",
                                    call.name,
                                    "toolset",
                                    call.toolset,
                                    "goal_generation",
                                    call.goalGeneration,
                                    "model_turn",
                                    call.modelTurn,
                                    "input_error",
                                    call.inputError,
                                    "state",
                                    call.state.name(),
                                    "outcome",
                                    call.settlement == null
                                            ? "settlement_unavailable"
                                            : call.settlement.outcome(),
                                    "error",
                                    call.settlement == null ? null : call.settlement.error(),
                                    "artifact",
                                    call.settlement == null ? null : call.settlement.artifact(),
                                    "confirmed_at",
                                    call.settlement == null ? null : string(call.settlement.confirmedAt())))
                            .toList());
            envelope.put(
                    "output_reports",
                    snapshot.outputReports.stream().map(
                            report -> fields(
                                    "report_id",
                                    report.reportId(),
                                    "goal_generation",
                                    report.goalGeneration(),
                                    "filename",
                                    report.filename(),
                                    "media_type",
                                    report.mediaType(),
                                    "byte_size",
                                    report.byteSize(),
                                    "checksum",
                                    report.checksum(),
                                    "coverage",
                                    report.coverage()))
                            .toList());
            envelope.put("blockers", List.copyOf(snapshot.blockers));
            envelope.put("excluded_content", conversion.excluded);
            return new Result(
                    true,
                    List.of(InferenceMessage.user(JSON.writeValueAsString(envelope))),
                    conversion.excluded.isEmpty()
                            ? "portable_handoff"
                            : "portable_handoff_reasoning_excluded",
                    conversion.excluded.size());
        } catch (UnsupportedContent unsupported) {
            return new Result(false, List.of(), "unsupported_content", conversion.excluded.size());
        } catch (Exception invalid) {
            return new Result(false, List.of(), "invalid_context", conversion.excluded.size());
        }
    }

    private static final class Conversion {

        private final List<Map<String, Object>> excluded = new ArrayList<>();

        private List<Map<String, Object>> content(List<InferenceContent> contents) {
            var result = new ArrayList<Map<String, Object>>();
            for (InferenceContent content : contents) {
                switch (content) {
                    case InferenceContent.Text text -> result.add(
                            fields(
                                    "kind",
                                    "visible_text",
                                    "text",
                                    text.text(),
                                    "citations",
                                    text.citations()));
                    case InferenceContent.ToolCall call -> result.add(
                            fields(
                                    "kind",
                                    "historical_tool_call",
                                    "call_id",
                                    call.id(),
                                    "name",
                                    call.name(),
                                    "input",
                                    call.input(),
                                    "toolset",
                                    call.toolset()));
                    case InferenceContent.ToolResult settlement -> result.add(
                            fields(
                                    "kind",
                                    "historical_tool_result",
                                    "call_id",
                                    settlement.toolUseId(),
                                    "error",
                                    settlement.error(),
                                    "toolset",
                                    settlement.toolset(),
                                    "content",
                                    content(settlement.content())));
                    case InferenceContent.Thinking ignored -> exclude("native", "thinking");
                    case InferenceContent.ProviderBlock block -> result.addAll(provider(block));
                    case InferenceContent.Image ignored -> throw new UnsupportedContent();
                    case InferenceContent.Document ignored -> throw new UnsupportedContent();
                }
            }
            return result;
        }

        private List<Map<String, Object>> provider(InferenceContent.ProviderBlock block) {
            // Extract only documented visible representations. Unknown native fields cannot cross providers.
            var data = block.data();
            if (Set.of("reasoning", "thinking", "redacted_thinking").contains(block.type())) {
                exclude(block.provider(), block.type());
                return List.of();
            }
            if (Set.of("openai", "llama-cpp").contains(block.provider())) {
                if ("message".equals(block.type())) {
                    requireFields(data, "id", "type", "role", "status", "phase", "content");
                    if (!(data.get("content") instanceof List<?> parts)) {
                        throw new UnsupportedContent();
                    }
                    var visible = new ArrayList<Map<String, Object>>();
                    for (Object part : parts) {
                        if (!(part instanceof Map<?, ?> fields)) {
                            throw new UnsupportedContent();
                        }
                        requireFields(fields, "type", "text", "annotations", "logprobs", "refusal");
                        if (fields.get("logprobs") != null && !List.of().equals(fields.get("logprobs"))) {
                            throw new UnsupportedContent();
                        }
                        String type = Objects.toString(fields.get("type"), "");
                        if ("output_text".equals(type) && fields.get("text") instanceof String text) {
                            visible.add(
                                    fields(
                                            "kind",
                                            "visible_text",
                                            "text",
                                            text,
                                            "citations",
                                            fields.getOrDefault("annotations", null),
                                            "provider",
                                            block.provider(),
                                            "native_message_id",
                                            data.get("id"),
                                            "status",
                                            data.get("status"),
                                            "phase",
                                            data.get("phase")));
                        } else if ("refusal".equals(type)
                                && fields.get("refusal") instanceof String refusal) {
                            visible.add(
                                    fields(
                                            "kind",
                                            "visible_refusal",
                                            "text",
                                            refusal,
                                            "provider",
                                            block.provider(),
                                            "native_message_id",
                                            data.get("id"),
                                            "status",
                                            data.get("status"),
                                            "phase",
                                            data.get("phase"),
                                            "citations",
                                            fields.get("annotations")));
                        } else {
                            throw new UnsupportedContent();
                        }
                    }
                    return visible;
                }
                if ("function_call".equals(block.type())) {
                    requireFields(data, "id", "type", "call_id", "name", "arguments", "status", "namespace");
                    if (!(data.get("call_id") instanceof String callId)
                            || !(data.get("name") instanceof String name)
                            || !(data.get("arguments") instanceof String arguments)) {
                        throw new UnsupportedContent();
                    }
                    // Preserve original arguments even when malformed: accepted-call state records its rejection.
                    return List.of(
                            fields(
                                    "kind",
                                    "historical_tool_call",
                                    "call_id",
                                    callId,
                                    "name",
                                    name,
                                    "arguments",
                                    arguments,
                                    "provider",
                                    block.provider(),
                                    "native_item_id",
                                    data.get("id"),
                                    "status",
                                    data.get("status"),
                                    "namespace",
                                    data.get("namespace")));
                }
            }
            if ("claude".equals(block.provider()) && "text".equals(block.type())) {
                requireFields(data, "type", "text", "citations");
                if (!(data.get("text") instanceof String text)) {
                    throw new UnsupportedContent();
                }
                return List.of(
                        fields(
                                "kind",
                                "visible_text",
                                "text",
                                text,
                                "citations",
                                data.get("citations"),
                                "provider",
                                block.provider()));
            }
            throw new UnsupportedContent();
        }

        private void exclude(String provider, String type) {
            excluded.add(
                    fields(
                            "provider",
                            provider,
                            "type",
                            type,
                            "outcome",
                            "private_reasoning_not_transferred"));
        }
    }

    private static void requireFields(Map<?, ?> value, String... fields) {
        if (!Set.of(fields).containsAll(value.keySet())) {
            throw new UnsupportedContent();
        }
    }

    private static Map<String, Object> source(SourceTurn source) {
        return source == null
                ? null
                : fields(
                        "workspace_id",
                        source.workspaceId(),
                        "channel_id",
                        source.channelId(),
                        "thread_id",
                        source.threadId(),
                        "message_id",
                        source.messageId());
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    private static Map<String, Object> fields(Object... values) {
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < values.length; i += 2) {
            result.put((String) values[i], values[i + 1]);
        }
        return result;
    }

    private static final class UnsupportedContent extends RuntimeException {}
}
