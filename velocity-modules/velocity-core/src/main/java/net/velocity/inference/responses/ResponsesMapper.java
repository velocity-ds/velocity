package net.velocity.inference.responses;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.openai.core.ObjectMappers;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseStreamEvent;

import net.velocity.inference.internal.UsageDetails;
import net.velocity.inference.objects.InferenceContent;
import net.velocity.inference.objects.InferenceEvent;
import net.velocity.inference.objects.InferenceMessage;
import net.velocity.inference.objects.InferenceResponse;
import net.velocity.inference.objects.InferenceResponseException;

/** Maps shared Responses API items while preserving their originating provider. */
final class ResponsesMapper {

    private static final JsonMapper JSON = ObjectMappers.jsonMapper();
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> OBJECTS = new TypeReference<>() {};
    private static final Set<String> INPUT_CONTENT = Set.of("input_text", "input_image", "input_file");
    private static final Set<String> MESSAGE_FIELDS = Set.of("type", "id", "role", "status", "content");
    private static final Set<String> TEXT_FIELDS = Set.of("type", "text", "annotations", "logprobs");

    private ResponsesMapper() {
    }

    static List<ResponseInputItem> input(List<InferenceMessage> messages, String providerId) {
        requireProvider(providerId);
        List<ResponseInputItem> items = new ArrayList<>();
        for (InferenceMessage message : messages) {
            List<Map<String, Object>> parts = new ArrayList<>();
            for (InferenceContent content : message.content()) {
                if (content instanceof InferenceContent.Text || content instanceof InferenceContent.Image
                        || content instanceof InferenceContent.Document) {
                    parts.add(inputContent(content, providerId));
                } else if (content instanceof InferenceContent.ProviderBlock provider
                        && INPUT_CONTENT.contains(provider.type())) {
                    parts.add(providerData(provider, providerId));
                } else {
                    flushMessage(items, parts, message.role());
                    items.add(JSON.convertValue(inputItem(content, providerId), ResponseInputItem.class));
                }
            }
            flushMessage(items, parts, message.role());
        }
        return List.copyOf(items);
    }

    private static void flushMessage(
            List<ResponseInputItem> items,
            List<Map<String, Object>> parts,
            InferenceMessage.Role role) {
        if (!parts.isEmpty()) {
            items.add(
                    JSON.convertValue(
                            Map.of(
                                    "type",
                                    "message",
                                    "role",
                                    role == InferenceMessage.Role.USER ? "user" : "assistant",
                                    "content",
                                    List.copyOf(parts)),
                            ResponseInputItem.class));
            parts.clear();
        }
    }

    private static Map<String, Object> inputContent(InferenceContent content, String providerId) {
        return switch (content) {
            case InferenceContent.Text text -> {
                if (!text.citations().isEmpty()) {
                    throw new UnsupportedOperationException(
                            providerName(providerId)
                                    + " cited text must be replayed in its original message ProviderBlock");
                }
                yield Map.of("type", "input_text", "text", text.text());
            }
            case InferenceContent.Image image -> Map.of(
                    "type",
                    "input_image",
                    "image_url",
                    imageUrl(image),
                    "detail",
                    "auto");
            case InferenceContent.Document document -> Map.of(
                    "type",
                    "input_file",
                    "filename",
                    document.filename() == null ? "document.pdf" : document.filename(),
                    "file_data",
                    "data:" + document.mediaType() + ";base64," + document.base64());
            case InferenceContent.ProviderBlock provider -> {
                Map<String, Object> data = providerData(provider, providerId);
                if (!INPUT_CONTENT.contains(provider.type())) {
                    throw new UnsupportedOperationException(
                            providerName(providerId) + " cannot use " + provider.type()
                                    + " as input content");
                }
                yield data;
            }
            default -> throw new UnsupportedOperationException(
                    providerName(providerId) + " tool results support only text, images, documents, and "
                            + providerName(providerId) + " input content");
        };
    }

    private static Map<String, Object> inputItem(InferenceContent content, String providerId) {
        return switch (content) {
            case InferenceContent.ToolCall call -> {
                if (call.toolset() != null) {
                    throw new UnsupportedOperationException(
                            providerName(providerId) + " toolset calls must use "
                                    + ("openai".equals(providerId) ? "an " : "a ")
                                    + providerName(providerId) + " ProviderBlock");
                }
                yield Map.of(
                        "type",
                        "function_call",
                        "call_id",
                        call.id(),
                        "name",
                        call.name(),
                        "arguments",
                        json(call.input(), providerId));
            }
            case InferenceContent.ToolResult result -> toolResult(result, providerId);
            case InferenceContent.ProviderBlock provider -> {
                Map<String, Object> data = providerData(provider, providerId);
                if (Set.of("output_text", "refusal", "summary_text", "reasoning_text")
                        .contains(provider.type())) {
                    throw new UnsupportedOperationException(
                            providerName(providerId)
                                    + " output content must be replayed in its original message or reasoning ProviderBlock");
                }
                yield data;
            }
            case InferenceContent.Thinking ignored -> throw new UnsupportedOperationException(
                    providerName(providerId) + " cannot replay signed thinking; use the original "
                            + providerName(providerId) + " reasoning ProviderBlock");
            default -> throw new IllegalArgumentException(
                    "Expected "
                            + ("openai".equals(providerId) ? "an " : "a ") + providerName(providerId)
                            + " input item");
        };
    }

    private static Map<String, Object> toolResult(InferenceContent.ToolResult result, String providerId) {
        if ("computer".equals(result.toolset())) {
            if (result.error() || result.content().size() != 1
                    || !(result.content().getFirst() instanceof InferenceContent.Image image)) {
                throw new UnsupportedOperationException(
                        providerName(providerId)
                                + " computer results require one screenshot; use a computer_call_output ProviderBlock for other fields");
            }
            return Map.of(
                    "type",
                    "computer_call_output",
                    "call_id",
                    result.toolUseId(),
                    "output",
                    Map.of("type", "computer_screenshot", "image_url", imageUrl(image)));
        }
        if (result.toolset() != null) {
            throw new UnsupportedOperationException(
                    providerName(providerId) + " does not support toolset " + result.toolset());
        }
        List<Map<String, Object>> parts = result.content().stream()
                .map(part -> inputContent(part, providerId)).toList();
        Object output;
        if (result.error()) {
            // Responses has no is_error flag. Keep the failure explicit in the tool's returned data.
            output = json(Map.of("error", true, "content", parts), providerId);
        } else if (result.content().stream().allMatch(InferenceContent.Text.class::isInstance)) {
            output = result.content().stream().map(InferenceContent.Text.class::cast)
                    .map(InferenceContent.Text::text).collect(Collectors.joining());
        } else {
            output = parts;
        }
        return Map.of("type", "function_call_output", "call_id", result.toolUseId(), "output", output);
    }

    private static String imageUrl(InferenceContent.Image image) {
        return "data:" + image.mediaType() + ";base64," + image.base64();
    }

    private static Map<String, Object> providerData(
            InferenceContent.ProviderBlock provider,
            String providerId) {
        if (!providerId.equals(provider.provider())) {
            throw new UnsupportedOperationException(
                    providerName(providerId) + " cannot replay a block from " + provider.provider());
        }
        return provider.data();
    }

    static InferenceResponse response(Response response, String providerId) {
        requireProvider(providerId);
        return response(object(response), providerId);
    }

    private static InferenceResponse response(Map<String, Object> data, String providerId) {
        InferenceResponse.Usage usage = usage(data, providerId);
        try {
            return response(data, providerId, usage);
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw new InferenceResponseException(
                    "Malformed " + providerName(providerId) + " response",
                    malformed,
                    usage,
                    true,
                    data.get("model") instanceof String model ? model : null);
        }
    }

    private static InferenceResponse response(
            Map<String, Object> data,
            String providerId,
            InferenceResponse.Usage usage) {
        String status = (String) data.get("status");
        if ("failed".equals(status) || "cancelled".equals(status) || data.get("error") != null) {
            var failure = failure(providerName(providerId) + " response " + status, data.get("error"));
            throw new InferenceResponseException(
                    failure.getMessage(),
                    failure,
                    usage,
                    false,
                    data.get("model") instanceof String model ? model : null);
        }
        List<InferenceContent> content = new ArrayList<>();
        var text = new StringBuilder();
        if (data.get("output") != null) {
            for (Map<String, Object> item : objects(data.get("output"))) {
                text.append(messageText(item));
                if (plainMessage(item)) {
                    objects(item.get("content")).forEach(part -> content.add(content(part, providerId)));
                } else {
                    content.add(providerBlock(item, providerId));
                }
            }
        }
        String stopReason = status;
        if ("incomplete".equals(status) && data.get("incomplete_details") instanceof Map<?, ?> details
                && details.get("reason") instanceof String reason) {
            stopReason = reason;
        }
        return new InferenceResponse(
                (String) data.get("id"),
                (String) data.get("model"),
                content,
                stopReason,
                usage,
                text.toString());
    }

    private static InferenceResponse.Usage usage(Map<String, Object> data, String providerId) {
        if (!(data.get("usage") instanceof Map<?, ?> values)) {
            return null;
        }
        var details = new UsageDetails();
        long input = details.count(values, "input_tokens", true);
        long output = details.count(values, "output_tokens", true);
        // Responses cached input and reasoning output details are subsets of these totals.
        long total = values.get("total_tokens") == null
                ? Math.addExact(input, output)
                : details.count(values, "total_tokens", false);
        Map<?, ?> inputDetails = details.details(values, "input_tokens_details");
        Map<?, ?> outputDetails = details.details(values, "output_tokens_details");
        // Luna bills both cache categories independently; omitted counts cannot establish
        // that all input used the ordinary rate. Compatible servers retain their own contract.
        boolean requiresCacheCounts = "openai".equals(providerId) && "gpt-6-luna".equals(data.get("model"));
        long cached = details.count(inputDetails, "cached_tokens", requiresCacheCounts);
        long created = details.count(inputDetails, "cache_write_tokens", requiresCacheCounts);
        long reasoning = details.count(outputDetails, "reasoning_tokens", false);
        details.unknown(
                values,
                Set.of(
                        "input_tokens",
                        "output_tokens",
                        "total_tokens",
                        "input_tokens_details",
                        "output_tokens_details"),
                "");
        details.unknown(inputDetails, Set.of("cached_tokens", "cache_write_tokens"), "input_tokens_details.");
        details.unknown(outputDetails, Set.of("reasoning_tokens"), "output_tokens_details.");
        boolean terminal = "completed".equals(data.get("status")) || "incomplete".equals(data.get("status"));
        boolean finished = terminal || "failed".equals(data.get("status"))
                || "cancelled".equals(data.get("status"));
        // "auto" defers to project settings and does not confirm the standard rate. Wait for
        // final tier evidence: provisional tiers may resolve or downgrade to default later.
        if (finished && data.get("service_tier") != null && !"default".equals(data.get("service_tier"))) {
            details.unsupported("service_tier");
        }
        // Provider-hosted tools can have additional charges not represented by token totals.
        if (data.get("output") instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof Map<?, ?> fields && fields.get("type") instanceof String type
                        && Set.of(
                                "web_search_call",
                                "file_search_call",
                                "image_generation_call",
                                "code_interpreter_call").contains(type)) {
                    details.unsupported("hosted_tool." + type);
                }
            }
        }
        return new InferenceResponse.Usage(
                input,
                output,
                total,
                cached,
                created,
                0,
                0,
                reasoning,
                !terminal || details.incomplete(),
                details.unsupportedCategories());
    }

    private static String messageText(Map<String, Object> item) {
        if (!"message".equals(item.get("type")) || !(item.get("content") instanceof List<?> parts)) {
            return "";
        }
        var text = new StringBuilder();
        for (Object part : parts) {
            if (part instanceof Map<?, ?> fields && "output_text".equals(fields.get("type"))
                    && fields.get("text") instanceof String value) {
                text.append(value);
            }
        }
        return text.toString();
    }

    private static boolean plainMessage(Map<String, Object> item) {
        if (!"message".equals(item.get("type")) || !"assistant".equals(item.get("role"))
                || !MESSAGE_FIELDS.containsAll(item.keySet())
                || item.get("status") != null && !"completed".equals(item.get("status"))) {
            return false;
        }
        // Cited messages need their original output-message ID and status for valid input replay.
        // The same applies to refusal, phase, logprobs, and future fields with no portable representation.
        return item.get("content") != null && objects(item.get("content")).stream()
                .allMatch(part -> plainText(part) && empty(part.get("annotations")));
    }

    private static boolean plainText(Map<String, Object> part) {
        return "output_text".equals(part.get("type")) && TEXT_FIELDS.containsAll(part.keySet())
                && empty(part.get("logprobs"));
    }

    private static boolean empty(Object value) {
        return value == null || value instanceof List<?> list && list.isEmpty();
    }

    private static InferenceContent content(Map<String, Object> item, String providerId) {
        if (plainText(item)) {
            return new InferenceContent.Text(
                    (String) item.get("text"),
                    item.get("annotations") == null ? List.of() : objects(item.get("annotations")));
        }
        // Native reasoning, calls, refusals, and future items retain all fields for continuation.
        return providerBlock(item, providerId);
    }

    private static InferenceContent.ProviderBlock providerBlock(Map<String, Object> item, String providerId) {
        return new InferenceContent.ProviderBlock(providerId, (String) item.get("type"), item);
    }

    static InferenceEvent event(ResponseStreamEvent raw, String providerId) {
        requireProvider(providerId);
        Map<String, Object> metadata = object(raw);
        String name = (String) metadata.get("type");
        int index = metadata.get("output_index") instanceof Number number
                ? Math.toIntExact(number.longValue())
                : -1;
        InferenceEvent.Type type;
        String delta = null;
        InferenceContent content = null;
        InferenceResponse response = null;
        switch (name) {
            case "response.created" -> {
                type = InferenceEvent.Type.MESSAGE_START;
                response = response(object(metadata.get("response")), providerId);
            }
            case "response.queued", "response.in_progress" -> type = InferenceEvent.Type.MESSAGE_DELTA;
            case "response.output_item.added", "response.content_part.added",
                    "response.reasoning_summary_part.added" -> {
                type = InferenceEvent.Type.CONTENT_START;
                Object part = metadata.get(name.equals("response.output_item.added") ? "item" : "part");
                if (part != null) {
                    content = content(object(part), providerId);
                }
            }
            case "response.output_text.delta" -> {
                type = InferenceEvent.Type.TEXT_DELTA;
                delta = (String) metadata.get("delta");
            }
            case "response.reasoning_text.delta", "response.reasoning_summary_text.delta" -> {
                type = InferenceEvent.Type.THINKING_DELTA;
                delta = (String) metadata.get("delta");
            }
            case "response.function_call_arguments.delta", "response.custom_tool_call_input.delta" -> {
                type = InferenceEvent.Type.TOOL_INPUT_DELTA;
                delta = (String) metadata.get("delta");
            }
            case "response.output_text.annotation.added" -> type = InferenceEvent.Type.CITATION;
            case "response.output_item.done", "response.content_part.done",
                    "response.reasoning_summary_part.done" -> {
                type = InferenceEvent.Type.CONTENT_STOP;
                Object part = metadata.get(name.equals("response.output_item.done") ? "item" : "part");
                if (part != null) {
                    content = content(object(part), providerId);
                }
            }
            case "response.completed", "response.incomplete" -> {
                type = InferenceEvent.Type.COMPLETE;
                // Terminal events carry the authoritative response, including encrypted reasoning and usage.
                response = response(object(metadata.get("response")), providerId);
            }
            case "response.failed" -> {
                var failed = object(metadata.get("response"));
                var failure = failure(providerName(providerId) + " response failed", failed.get("error"));
                throw new InferenceResponseException(
                        failure.getMessage(),
                        failure,
                        usage(failed, providerId),
                        false,
                        failed.get("model") instanceof String model ? model : null);
            }
            case "error" -> throw failure(
                    providerName(providerId) + " stream error",
                    metadata.get("error") == null ? metadata : metadata.get("error"));
            default -> type = InferenceEvent.Type.OTHER;
        }
        return new InferenceEvent(type, index, delta, content, response, metadata);
    }

    private static IllegalStateException failure(String message, Object error) {
        if (error instanceof Map<?, ?> details) {
            if (details.get("code") != null) {
                message += " (" + details.get("code") + ")";
            }
            if (details.get("message") != null) {
                message += ": " + details.get("message");
            }
        }
        return new IllegalStateException(message);
    }

    private static String json(Object value, String providerId) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException(
                    "Cannot serialize " + providerName(providerId) + " input",
                    failure);
        }
    }

    private static String providerName(String providerId) {
        return switch (providerId) {
            case "openai" -> "OpenAI";
            case "llama-cpp" -> "llama.cpp";
            default -> providerId;
        };
    }

    private static void requireProvider(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("Responses provider ID must not be blank");
        }
    }

    private static Map<String, Object> object(Object value) {
        return JSON.convertValue(value, OBJECT);
    }

    private static List<Map<String, Object>> objects(Object value) {
        return JSON.convertValue(value, OBJECTS);
    }
}
