package net.velocity.inference.objects;

import java.util.Map;
import java.util.Objects;

/** A stream update; COMPLETE includes the assembled response, tool calls, stop reason, and usage. */
public record InferenceEvent(
        Type type,
        int contentIndex,
        String delta,
        InferenceContent content,
        InferenceResponse response,
        Map<String, Object> metadata) {

    public enum Type {
        MESSAGE_START, CONTENT_START, TEXT_DELTA, THINKING_DELTA, SIGNATURE_DELTA, TOOL_INPUT_DELTA, CITATION, CONTENT_STOP, MESSAGE_DELTA, COMPLETE, OTHER
    }

    public InferenceEvent {
        Objects.requireNonNull(type, "type");
        metadata = InferenceJson.copy(metadata);
    }
}
