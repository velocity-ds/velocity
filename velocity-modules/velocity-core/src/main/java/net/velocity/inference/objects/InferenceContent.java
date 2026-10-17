package net.velocity.inference.objects;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Portable conversation content, with an explicit escape hatch for provider-specific blocks. */
public sealed interface InferenceContent {

    record Text(String text, List<Map<String, Object>> citations) implements InferenceContent {

        public Text {
            Objects.requireNonNull(text, "text");
            citations = citations == null ? List.of() : citations.stream().map(InferenceJson::copy).toList();
        }

        public Text(String text) {
            this(text, List.of());
        }
    }

    /** Raw Base64 image data, without a data URL prefix. */
    record Image(String base64, String mediaType) implements InferenceContent {

        public Image {
            InferenceJson.requireText(base64, "Image data");
            if (mediaType == null
                    || !Set.of("image/png", "image/jpeg", "image/gif", "image/webp").contains(mediaType)) {
                throw new IllegalArgumentException("Unsupported image media type: " + mediaType);
            }
        }
    }

    /**
     * Raw Base64 file data sent to the provider as a native document input, without any local conversion.
     * Only PDF is portable across the document-capable providers; the filename is an optional display title.
     */
    record Document(String base64, String mediaType, String filename) implements InferenceContent {

        public Document {
            InferenceJson.requireText(base64, "Document data");
            if (!"application/pdf".equals(mediaType)) {
                throw new IllegalArgumentException("Unsupported document media type: " + mediaType);
            }
            if (filename != null && (filename.isBlank() || filename.length() > 255
                    || filename.codePoints().anyMatch(Character::isISOControl))) {
                throw new IllegalArgumentException("Document filename must be short printable text");
            }
        }
    }

    record Thinking(String text, String signature) implements InferenceContent {

        public Thinking {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(signature, "signature");
        }
    }

    record ToolCall(String id, String name, Map<String, Object> input, String toolset)
            implements
                InferenceContent {

        public ToolCall {
            InferenceJson.requireText(id, "Tool call ID");
            InferenceJson.requireText(name, "Tool name");
            input = InferenceJson.copy(input);
        }
    }

    record ToolResult(String toolUseId, List<InferenceContent> content, boolean error, String toolset)
            implements
                InferenceContent {

        public ToolResult {
            InferenceJson.requireText(toolUseId, "Tool call ID");
            content = InferenceJson.content(content, "Tool result content");
        }
    }

    /**
     * Lossless JSON for content without a common representation, such as server search results.
     * Only the originating provider may replay this block as conversation history.
     */
    record ProviderBlock(String provider, String type, Map<String, Object> data) implements InferenceContent {

        public ProviderBlock {
            InferenceJson.requireText(provider, "Provider");
            InferenceJson.requireText(type, "Block type");
            data = InferenceJson.copy(data);
            if (!type.equals(data.get("type"))) {
                throw new IllegalArgumentException("Provider block data must include its matching type");
            }
        }
    }
}
