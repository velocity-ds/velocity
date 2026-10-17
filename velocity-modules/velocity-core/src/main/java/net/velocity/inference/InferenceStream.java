package net.velocity.inference;

import java.util.stream.Stream;

import net.velocity.inference.objects.InferenceEvent;

/** A single-use response stream. Always consume it inside try-with-resources. */
public interface InferenceStream extends AutoCloseable {

    Stream<InferenceEvent> events();

    @Override
    void close();
}
