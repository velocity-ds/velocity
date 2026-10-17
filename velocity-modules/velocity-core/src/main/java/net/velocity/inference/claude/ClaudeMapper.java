package net.velocity.inference.claude;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.anthropic.core.ObjectMappers;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.StopReason;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.json.JsonMapper;

import net.velocity.inference.internal.UsageDetails;
import net.velocity.inference.objects.InferenceContent;
import net.velocity.inference.objects.InferenceEvent;
import net.velocity.inference.objects.InferenceMessage;
import net.velocity.inference.objects.InferenceResponse;
import net.velocity.inference.objects.InferenceResponseException;

/** Keeps Anthropic SDK classes and wire representations inside the provider boundary. */
final class ClaudeMapper {

    private static final JsonMapper JSON = ObjectMappers.jsonMapper();
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> OBJECTS = new TypeReference<>() {};

    private ClaudeMapper() {
    }

    /** The ephemeral cache marker, as a wire map so it can be added to any cacheable block or tool. */
    static final Map<String, Object> CACHE_CONTROL = Map.of("type", "ephemeral");
    /** Block types that accept a cache marker; thinking and redacted blocks do not. */
    private static final Set<String> CACHEABLE_BLOCKS = Set
            .of("text", "image", "document", "tool_use", "tool_result");

    static MessageParam message(InferenceMessage message) {
        return message(message, false);
    }

    /** With {@code cacheBreakpoint}, the last cacheable block of the message carries the cache marker. */
    static MessageParam message(InferenceMessage message, boolean cacheBreakpoint) {
        List<Map<String, Object>> blocks = message.content().stream().map(ClaudeMapper::wireContent).toList();
        if (cacheBreakpoint) {
            for (int index = blocks.size() - 1; index >= 0; index--) {
                if (CACHEABLE_BLOCKS.contains(blocks.get(index).get("type"))) {
                    var marked = new LinkedHashMap<>(blocks.get(index));
                    marked.put("cache_control", CACHE_CONTROL);
                    var copy = new java.util.ArrayList<>(blocks);
                    copy.set(index, marked);
                    blocks = copy;
                    break;
                }
            }
        }
        return MessageParam.builder()
                .role(
                        message.role() == InferenceMessage.Role.USER
                                ? MessageParam.Role.USER
                                : MessageParam.Role.ASSISTANT)
                .contentOfBlockParams(
                        blocks.stream().map(block -> JSON.convertValue(block, ContentBlockParam.class))
                                .toList())
                .build();
    }

    private static Map<String, Object> wireContent(InferenceContent content) {
        Map<String, Object> block = new LinkedHashMap<>();
        switch (content) {
            case InferenceContent.Text text -> {
                block.put("type", "text");
                block.put("text", text.text());
                if (!text.citations().isEmpty()) {
                    block.put("citations", text.citations());
                }
            }
            case InferenceContent.Image image -> {
                block.put("type", "image");
                block.put(
                        "source",
                        Map.of("type", "base64", "data", image.base64(), "media_type", image.mediaType()));
            }
            case InferenceContent.Document document -> {
                block.put("type", "document");
                block.put(
                        "source",
                        Map.of(
                                "type",
                                "base64",
                                "data",
                                document.base64(),
                                "media_type",
                                document.mediaType()));
                if (document.filename() != null) {
                    block.put("title", document.filename());
                }
            }
            case InferenceContent.Thinking thinking -> {
                block.put("type", "thinking");
                block.put("thinking", thinking.text());
                block.put("signature", thinking.signature());
            }
            case InferenceContent.ToolCall tool -> {
                block.put("type", "tool_use");
                block.put("id", tool.id());
                block.put("name", tool.name());
                block.put("input", tool.input());
                if (tool.toolset() != null) {
                    block.put("toolset_name", tool.toolset());
                }
            }
            case InferenceContent.ToolResult result -> {
                block.put("type", "tool_result");
                block.put("tool_use_id", result.toolUseId());
                block.put("content", result.content().stream().map(ClaudeMapper::wireContent).toList());
                block.put("is_error", result.error());
                if (result.toolset() != null) {
                    block.put("toolset_name", result.toolset());
                }
            }
            case InferenceContent.ProviderBlock provider -> {
                if (!"claude".equals(provider.provider())) {
                    throw new UnsupportedOperationException(
                            "Claude cannot replay a block from " + provider.provider());
                }
                block.putAll(provider.data());
            }
        }
        return block;
    }

    static InferenceResponse response(Message message) {
        return response(message, usage(object(message).get("usage"), false));
    }

    private static InferenceResponse response(Message message, InferenceResponse.Usage usage) {
        try {
            return new InferenceResponse(
                    message.id(),
                    message.model().asString(),
                    message.content().stream().map(ClaudeMapper::object).map(ClaudeMapper::content).toList(),
                    message.stopReason().map(StopReason::asString).orElse(null),
                    usage,
                    message.content().stream()
                            .filter(block -> block.isText())
                            .map(block -> block.asText().text())
                            .collect(Collectors.joining()));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            var data = object(message);
            throw new InferenceResponseException(
                    "Malformed Claude response",
                    malformed,
                    usage,
                    true,
                    data.get("model") instanceof String model ? model : null);
        }
    }

    private static InferenceResponse.Usage usage(Object value, boolean incomplete) {
        if (!(value instanceof Map<?, ?> fields)) {
            return null;
        }
        var details = new UsageDetails();
        long uncached = details.count(fields, "input_tokens", true);
        long output = details.count(fields, "output_tokens", true);
        long created = details.count(fields, "cache_creation_input_tokens", false);
        long cached = details.count(fields, "cache_read_input_tokens", false);
        Map<?, ?> creation = details.details(fields, "cache_creation");
        long fiveMinutes = details.count(creation, "ephemeral_5m_input_tokens", false);
        long oneHour = details.count(creation, "ephemeral_1h_input_tokens", false);
        Map<?, ?> outputDetails = details.details(fields, "output_tokens_details");
        long reasoning = details.count(outputDetails, "thinking_tokens", false);
        details.unknown(
                creation,
                Set.of("ephemeral_5m_input_tokens", "ephemeral_1h_input_tokens"),
                "cache_creation.");
        details.unknown(outputDetails, Set.of("thinking_tokens"), "output_tokens_details.");
        details.unknown(
                fields,
                Set.of(
                        "input_tokens",
                        "output_tokens",
                        "cache_creation_input_tokens",
                        "cache_read_input_tokens",
                        "cache_creation",
                        "output_tokens_details",
                        "service_tier",
                        "inference_geo"),
                "");
        if (fields.get("service_tier") != null && !"standard".equals(fields.get("service_tier"))) {
            details.unsupported("service_tier");
        }
        if (fields.get("inference_geo") != null
                && !Set.of("global", "not_available").contains(fields.get("inference_geo"))) {
            details.unsupported("inference_geo");
        }
        // Anthropic's input count excludes cache reads and writes. Duration subtotals are
        // already in the creation aggregate; thinking is already in output_tokens.
        long input = Math.addExact(uncached, Math.addExact(created, cached));
        return new InferenceResponse.Usage(
                input,
                output,
                Math.addExact(input, output),
                cached,
                created,
                fiveMinutes,
                oneHour,
                reasoning,
                incomplete || details.incomplete(),
                details.unsupportedCategories());
    }

    private static InferenceContent content(Map<String, Object> block) {
        String type = (String) block.get("type");
        // Preserve future fields and execution provenance instead of silently dropping them on replay.
        Set<String> portableFields = switch (type) {
            case "text" -> Set.of("type", "text", "citations");
            case "thinking" -> Set.of("type", "thinking", "signature");
            case "tool_use" -> Set.of("type", "id", "name", "input", "toolset_name", "caller");
            default -> Set.of();
        };
        if (!portableFields.containsAll(block.keySet())
                || "tool_use".equals(type) && (block.get("caller") != null
                        && !Map.of("type", "direct").equals(block.get("caller"))
                        || !(block.get("id") instanceof String id) || id.isBlank()
                        || !(block.get("name") instanceof String name) || name.isBlank()
                        || !(block.get("input") instanceof Map<?, ?>))) {
            return new InferenceContent.ProviderBlock("claude", type, block);
        }
        return switch (type) {
            case "text" -> new InferenceContent.Text(
                    (String) block.get("text"),
                    block.get("citations") == null
                            ? List.of()
                            : JSON.convertValue(block.get("citations"), OBJECTS));
            case "thinking" -> new InferenceContent.Thinking(
                    (String) block.get("thinking"),
                    (String) block.get("signature"));
            case "tool_use" -> new InferenceContent.ToolCall(
                    (String) block.get("id"),
                    (String) block.get("name"),
                    object(block.get("input")),
                    (String) block.get("toolset_name"));
            // Server-side calls, results, redacted thinking, and future blocks must replay unchanged.
            default -> new InferenceContent.ProviderBlock("claude", type, block);
        };
    }

    static InferenceEvent event(
            RawMessageStreamEvent raw,
            MessageAccumulator accumulator,
            StreamUsage streamUsage) {
        Map<String, Object> metadata = object(raw);
        streamUsage.observe(metadata);
        int index = metadata.get("index") instanceof Number number ? Math.toIntExact(number.longValue()) : -1;
        InferenceEvent.Type type;
        String delta = null;
        InferenceContent content = null;
        InferenceResponse response = null;
        if (raw.isMessageStart()) {
            type = InferenceEvent.Type.MESSAGE_START;
            response = response(raw.asMessageStart().message(), streamUsage.usage(true));
        } else if (raw.isContentBlockStart()) {
            type = InferenceEvent.Type.CONTENT_START;
            content = content(object(raw.asContentBlockStart().contentBlock()));
        } else if (raw.isContentBlockDelta()) {
            Map<String, Object> data = object(raw.asContentBlockDelta().delta());
            String deltaType = (String) data.get("type");
            type = switch (deltaType) {
                case "text_delta" -> InferenceEvent.Type.TEXT_DELTA;
                case "thinking_delta" -> InferenceEvent.Type.THINKING_DELTA;
                case "signature_delta" -> InferenceEvent.Type.SIGNATURE_DELTA;
                case "input_json_delta" -> InferenceEvent.Type.TOOL_INPUT_DELTA;
                case "citations_delta" -> InferenceEvent.Type.CITATION;
                default -> InferenceEvent.Type.OTHER;
            };
            delta = switch (type) {
                case TEXT_DELTA -> (String) data.get("text");
                case THINKING_DELTA -> (String) data.get("thinking");
                case SIGNATURE_DELTA -> (String) data.get("signature");
                case TOOL_INPUT_DELTA -> (String) data.get("partial_json");
                default -> null;
            };
        } else if (raw.isContentBlockStop()) {
            type = InferenceEvent.Type.CONTENT_STOP;
        } else if (raw.isMessageDelta()) {
            type = InferenceEvent.Type.MESSAGE_DELTA;
            response = new InferenceResponse(
                    streamUsage.id,
                    streamUsage.model,
                    List.of(),
                    null,
                    streamUsage.usage(true),
                    "");
        } else if (raw.isMessageStop()) {
            type = InferenceEvent.Type.COMPLETE;
            response = response(accumulator.message(), streamUsage.usage(false));
        } else {
            type = InferenceEvent.Type.OTHER;
        }
        return new InferenceEvent(type, index, delta, content, response, metadata);
    }

    /** The SDK's content accumulator drops additional usage fields from deltas. Preserve them
     * independently, replacing cumulative counts rather than adding repeated observations. */
    static final class StreamUsage {

        private String id;
        private String model;
        private Map<String, Object> fields;
        private boolean outputUsageReported;

        private void observe(Map<String, Object> event) {
            if ("message_start".equals(event.get("type"))
                    && event.get("message") instanceof Map<?, ?> message) {
                id = message.get("id") instanceof String value ? value : null;
                model = message.get("model") instanceof String value ? value : null;
                if (message.get("usage") instanceof Map<?, ?> values) {
                    fields = merge(Map.of(), values);
                }
            } else if ("message_delta".equals(event.get("type"))
                    && event.get("usage") instanceof Map<?, ?> values) {
                fields = merge(fields == null ? Map.of() : fields, values);
                outputUsageReported |= values.get("output_tokens") != null;
            }
        }

        private InferenceResponse.Usage usage(boolean incomplete) {
            // Start-event output usage is provisional. Even a message_stop cannot replace a
            // missing final usage report; an explicitly reported zero is valid final usage.
            return ClaudeMapper.usage(fields, incomplete || !outputUsageReported);
        }

        private static Map<String, Object> merge(Map<?, ?> previous, Map<?, ?> update) {
            Map<String, Object> merged = new LinkedHashMap<>();
            previous.forEach((key, value) -> merged.put(key.toString(), value));
            update.forEach((key, value) -> {
                Object old = merged.get(key.toString());
                merged.put(
                        key.toString(),
                        old instanceof Map<?, ?> oldFields && value instanceof Map<?, ?> newFields
                                ? merge(oldFields, newFields)
                                : value);
            });
            return merged;
        }
    }

    private static Map<String, Object> object(Object value) {
        return JSON.convertValue(value, OBJECT);
    }
}
