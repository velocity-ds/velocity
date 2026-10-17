package net.velocity.agent.persistence;

import java.io.IOException;
import java.time.Instant;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;

/** Shared strict persistence encoding; each domain retains its own version and invariant checks. */
public final class PersistedJson {

    private PersistedJson() {
    }

    public static JsonMapper.Builder builder() {
        var time = new SimpleModule();
        time.addSerializer(Instant.class, new JsonSerializer<>() {

            @Override
            public void serialize(Instant value, JsonGenerator generator, SerializerProvider provider)
                    throws IOException {
                generator.writeString(value.toString());
            }
        });
        time.addDeserializer(Instant.class, new JsonDeserializer<>() {

            @Override
            public Instant deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                try {
                    return Instant.parse(parser.getValueAsString());
                } catch (RuntimeException failure) {
                    throw new IOException("Invalid persisted timestamp", failure);
                }
            }
        });
        return JsonMapper.builder().addModule(time)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }
}
