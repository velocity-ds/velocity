package net.velocity.agent.http;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

/** Strict parsing mechanics. Adapters retain schemas, body policies, authority and domain error messages. */
public final class StrictRequests {

    private static final TypeReference<LinkedHashMap<String, Object>> OBJECT = new TypeReference<>() {};
    private static final JsonMapper STANDARD = mapper(false);
    private static final JsonMapper PRECISE = mapper(true);

    private StrictRequests() {
    }

    private static JsonMapper mapper(boolean precise) {
        return JsonMapper.builder().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, precise).build();
    }

    public enum Numbers {
        STANDARD, PRECISE_FLOATS
    }

    public enum EmptyBody {
        STRICT, JSON_WHITESPACE, UNICODE_WHITESPACE
    }

    public static JsonNode tree(String body) throws IOException {
        return STANDARD.readTree(body);
    }

    public static Map<String, Object> object(String body, Numbers numbers) throws IOException {
        return mapper(numbers).readValue(body, OBJECT);
    }

    public static Map<String, Object> object(byte[] body, Numbers numbers) throws IOException {
        return mapper(numbers).readValue(body, OBJECT);
    }

    private static JsonMapper mapper(Numbers numbers) {
        return numbers == Numbers.PRECISE_FLOATS ? PRECISE : STANDARD;
    }

    public static void fields(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("Expected a JSON object");
        }
        node.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                throw new IllegalArgumentException("Unknown request field: " + field);
            }
        });
    }

    public static void fields(Map<String, Object> values, Set<String> allowed, Set<String> required) {
        if (values == null || !allowed.containsAll(values.keySet())
                || !values.keySet().containsAll(required) || values.containsValue(null)) {
            throw new IllegalArgumentException();
        }
    }

    public static void noParameters(Map<?, ?> parameters, String message) {
        if (!parameters.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
    }

    public static void parameters(Map<String, String[]> parameters, Set<String> allowed) {
        if (!allowed.containsAll(parameters.keySet())
                || parameters.values().stream().anyMatch(values -> values.length != 1)) {
            throw new IllegalArgumentException();
        }
    }

    public static void noQuery(String query, String message) {
        if (query != null) {
            throw new IllegalArgumentException(message);
        }
    }

    /** Callers erase the returned bytes in finally; partial/invalid buffers are erased here. */
    public static byte[] body(InputStream input, int maximumBytes, Integer maximumCharacters)
            throws IOException {
        byte[] bytes = input.readNBytes(Math.addExact(maximumBytes, 1));
        try {
            if (bytes.length > maximumBytes) {
                throw new IllegalArgumentException();
            }
            if (maximumCharacters != null) {
                var chars = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes));
                try {
                    if (chars.remaining() > maximumCharacters) {
                        throw new IllegalArgumentException();
                    }
                } finally {
                    if (chars.hasArray()) {
                        Arrays.fill(chars.array(), '\0');
                    }
                }
            }
            return bytes;
        } catch (IOException | RuntimeException invalid) {
            Arrays.fill(bytes, (byte) 0);
            throw invalid;
        }
    }

    public static void empty(String body, EmptyBody policy, String message) {
        boolean empty = body == null || switch (policy) {
            case STRICT -> body.isEmpty();
            case UNICODE_WHITESPACE -> body.isBlank();
            case JSON_WHITESPACE -> body.chars().allMatch(StrictRequests::jsonWhitespace);
        };
        if (!empty) {
            throw new IllegalArgumentException(message);
        }
    }

    public static void empty(byte[] body, String message) {
        for (byte value : body) {
            if (!jsonWhitespace(value)) {
                throw new IllegalArgumentException(message);
            }
        }
    }

    public static void strictlyEmpty(InputStream body, String message) throws IOException {
        if (body.read() != -1) {
            throw new IllegalArgumentException(message);
        }
    }

    private static boolean jsonWhitespace(int value) {
        return value == ' ' || value == '\t' || value == '\r' || value == '\n';
    }
}
