package net.velocity.operator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import net.velocity.agent.http.StrictRequests;
import net.velocity.inference.objects.InferenceContent;
import net.velocity.inference.objects.InferenceOptions;
import net.velocity.inference.objects.PromptPreset;

import jakarta.servlet.http.HttpServletRequest;

/** No provider, native history, tool results, function definitions, or authority comes from the client. */
final class PlaygroundRequests {

    static final JsonMapper JSON = JsonMapper.builder().build();

    private PlaygroundRequests() {
    }

    record Request(
            String prompt,
            String sessionId,
            Long expectedRevision,
            String systemPrompt,
            List<InferenceContent.Image> images,
            InferenceOptions options) {}

    static Request parse(String body) {
        try {
            JsonNode node = StrictRequests.tree(body);
            StrictRequests.fields(
                    node,
                    Set.of(
                            "prompt",
                            "sessionId",
                            "expectedRevision",
                            "systemPrompt",
                            "preset",
                            "images",
                            "outputSchema",
                            "webSearch",
                            "adaptiveThinking",
                            "computerUse"));
            String prompt = text(node, "prompt", true, 100_000);
            String session = text(node, "sessionId", false, 100);
            JsonNode revision = node.get("expectedRevision");
            if (revision != null && (!revision.isIntegralNumber() || !revision.canConvertToLong()
                    || revision.longValue() < 0)) {
                throw new IllegalArgumentException("expectedRevision must be a nonnegative integer");
            }
            if ((session == null) != (revision == null)) {
                throw new IllegalArgumentException(
                        "sessionId and expectedRevision must be supplied together");
            }
            String system = text(node, "systemPrompt", false, 30_000);
            String preset = text(node, "preset", false, 100);
            if (system != null && preset != null) {
                throw new IllegalArgumentException("Choose a preset or custom system instructions, not both");
            }
            if (preset != null) {
                try {
                    system = PromptPreset.valueOf(preset).prompt();
                } catch (IllegalArgumentException invalid) {
                    throw new IllegalArgumentException("Unknown prompt preset");
                }
            }
            List<InferenceContent.Image> images = new ArrayList<>();
            JsonNode inputImages = node.get("images");
            if (inputImages != null) {
                if (!inputImages.isArray() || inputImages.size() > 4) {
                    throw new IllegalArgumentException("images must be an array of at most four images");
                }
                for (JsonNode inputImage : inputImages) {
                    StrictRequests.fields(inputImage, Set.of("base64", "mediaType"));
                    String base64 = text(inputImage, "base64", true, 900_000);
                    try {
                        Base64.getDecoder().decode(base64);
                    } catch (IllegalArgumentException invalid) {
                        throw new IllegalArgumentException("Image data must be valid raw Base64");
                    }
                    images.add(new InferenceContent.Image(base64, text(inputImage, "mediaType", true, 30)));
                }
            }
            JsonNode schema = node.get("outputSchema");
            if (schema != null
                    && (!schema.isObject() || schema.isEmpty() || schema.toString().length() > 65_536)) {
                throw new IllegalArgumentException(
                        "outputSchema must be a nonempty JSON object up to 65536 characters");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> output = schema == null ? Map.of() : JSON.convertValue(schema, Map.class);
            return new Request(
                    prompt,
                    session,
                    revision == null ? null : revision.longValue(),
                    system,
                    List.copyOf(images),
                    new InferenceOptions(
                            output,
                            flag(node, "webSearch"),
                            flag(node, "adaptiveThinking"),
                            flag(node, "computerUse")));
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Invalid JSON request");
        }
    }

    static void noQuery(HttpServletRequest request) {
        StrictRequests.noQuery(request.getQueryString(), "This endpoint accepts no query parameters");
    }

    static void empty(HttpServletRequest request) throws IOException {
        noQuery(request);
        StrictRequests.strictlyEmpty(request.getInputStream(), "This endpoint accepts no request body");
    }

    private static String text(JsonNode node, String field, boolean required, int maximum) {
        JsonNode value = node.get(field);
        if (!required && (value == null || value.isNull())) {
            return null;
        }
        if (value == null || !value.isTextual() || value.textValue().isBlank()
                || value.textValue().length() > maximum) {
            throw new IllegalArgumentException(
                    field + " must be a nonempty string up to " + maximum + " characters");
        }
        return value.textValue();
    }

    private static boolean flag(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null) {
            return false;
        }
        if (!value.isBoolean()) {
            throw new IllegalArgumentException(field + " must be a boolean");
        }
        return value.booleanValue();
    }
}
