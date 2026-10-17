package net.velocity.inference.objects;

import java.util.List;
import java.util.Objects;

public record InferenceMessage(Role role, List<InferenceContent> content) {

    public enum Role {
        USER, ASSISTANT
    }

    public InferenceMessage {
        Objects.requireNonNull(role, "role");
        content = InferenceJson.content(content, "Message content");
    }

    public static InferenceMessage user(String input) {
        InferenceJson.requireText(input, "Inference input");
        return new InferenceMessage(Role.USER, List.of(new InferenceContent.Text(input)));
    }
}
