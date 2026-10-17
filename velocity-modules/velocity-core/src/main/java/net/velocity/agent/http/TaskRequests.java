package net.velocity.agent.http;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import net.velocity.agent.state.UploadedFile;

/** External DTOs intentionally expose no identity, instructions, history, or authority fields. */
public final class TaskRequests {

    /** Parse-time ceiling only; the configured upload limits are enforced when the files are stored. */
    private static final int MAX_FILE_ENTRIES = 100;

    private TaskRequests() {
    }

    public record Create(String request, String conversationId, List<UploadedFile> files) {}

    public record Update(String text) {}

    public record Answer(List<String> answers, Long expectedRevision) {}

    public record Delete(String expectedUpdatedAt) {}

    public record Continue(String text) {}

    public record Approval(String callId, boolean approved, Long expectedRevision) {}

    /** {@code {"callId": "...", "approved": true|false, "expectedRevision"?: n}}. */
    public static Approval approval(String body) {
        JsonNode json = object(body, Set.of("callId", "approved", "expectedRevision"));
        String callId = text(json, "callId", true);
        JsonNode approved = json.get("approved");
        if (approved == null || !approved.isBoolean()) {
            throw new IllegalArgumentException("approved must be true or false");
        }
        JsonNode revision = json.get("expectedRevision");
        if (revision != null && (!revision.isIntegralNumber() || !revision.canConvertToLong()
                || revision.longValue() < 0)) {
            throw new IllegalArgumentException("expectedRevision must be a nonnegative integer");
        }
        return new Approval(callId, approved.booleanValue(), revision == null ? null : revision.longValue());
    }

    public static Create create(String body) {
        JsonNode json = object(body, Set.of("request", "conversationId", "files"));
        return new Create(
                text(json, "request", true),
                text(json, "conversationId", false),
                files(json.get("files")));
    }

    /** Files travel as {@code {filename, mediaType?, contentBase64}} with strict Base64 content. */
    private static List<UploadedFile> files(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray() || node.size() > MAX_FILE_ENTRIES) {
            throw new IllegalArgumentException(
                    "files must be an array of at most " + MAX_FILE_ENTRIES + " entries");
        }
        List<UploadedFile> result = new ArrayList<>();
        for (JsonNode entry : node) {
            StrictRequests.fields(entry, Set.of("filename", "mediaType", "contentBase64"));
            String filename = text(entry, "filename", true);
            String mediaType = text(entry, "mediaType", false);
            String encoded = text(entry, "contentBase64", true);
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(encoded);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("contentBase64 for " + filename + " is not valid Base64");
            }
            result.add(new UploadedFile(filename, mediaType, bytes));
        }
        return List.copyOf(result);
    }

    public static Update update(String body) {
        return new Update(text(object(body, Set.of("text")), "text", true));
    }

    /** An optional instruction; {@code {}} continues with the saved request alone. */
    public static Continue continuation(String body) {
        return new Continue(text(object(body, Set.of("text")), "text", false));
    }

    public static Delete delete(String body) {
        return new Delete(text(object(body, Set.of("expectedUpdatedAt")), "expectedUpdatedAt", true));
    }

    public static Answer answer(String body) {
        JsonNode json = object(body, Set.of("answers", "expectedRevision"));
        List<String> answers = strings(json.get("answers"), "answers");
        JsonNode revision = json.get("expectedRevision");
        if (revision != null && (!revision.isIntegralNumber() || !revision.canConvertToLong()
                || revision.longValue() < 0)) {
            throw new IllegalArgumentException("expectedRevision must be a nonnegative integer");
        }
        if (answers.isEmpty()) {
            throw new IllegalArgumentException("Supply nonempty answers");
        }
        return new Answer(answers, revision == null ? null : revision.longValue());
    }

    private static JsonNode object(String body, Set<String> fields) {
        try {
            JsonNode node = StrictRequests.tree(body);
            StrictRequests.fields(node, fields);
            return node;
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("Invalid JSON request", exception);
        }
    }

    private static String text(JsonNode node, String name, boolean required) {
        JsonNode value = node.get(name);
        if (!required && (value == null || value.isNull())) {
            return null;
        }
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(name + " must be a nonempty string");
        }
        return value.textValue();
    }

    private static List<String> strings(JsonNode node, String name) {
        if (node == null) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException(name + " must be an array of strings");
        }
        List<String> result = new ArrayList<>();
        node.forEach(value -> {
            if (!value.isTextual() || value.textValue().isBlank()) {
                throw new IllegalArgumentException(name + " must contain nonempty strings");
            }
            result.add(value.textValue());
        });
        return List.copyOf(result);
    }
}
