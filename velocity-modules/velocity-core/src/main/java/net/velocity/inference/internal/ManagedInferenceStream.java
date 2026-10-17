package net.velocity.inference.internal;

import java.util.Iterator;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import net.velocity.inference.InferenceStream;
import net.velocity.inference.objects.InferenceEvent;

/** Internal lifecycle adapter for provider streams; decoding remains provider-specific. */
public final class ManagedInferenceStream<T> implements InferenceStream {

    private final String providerName;
    private final Supplier<Stream<T>> source;
    private final Function<T, InferenceEvent> decoder;
    private final Runnable closeSource;
    private boolean opened;
    private boolean closed;

    public ManagedInferenceStream(
            String providerName,
            Supplier<Stream<T>> source,
            Function<T, InferenceEvent> decoder,
            Runnable closeSource) {
        this.providerName = providerName;
        this.source = source;
        this.decoder = decoder;
        this.closeSource = closeSource;
    }

    @Override
    public Stream<InferenceEvent> events() {
        if (opened || closed) {
            throw new IllegalStateException(
                    "An inference stream can only be consumed once, before it is closed");
        }
        opened = true;
        try {
            Iterator<T> iterator = source.get().iterator();
            Spliterator<InferenceEvent> events = new Spliterators.AbstractSpliterator<>(
                    Long.MAX_VALUE,
                    Spliterator.ORDERED | Spliterator.NONNULL) {

                @Override
                public boolean tryAdvance(Consumer<? super InferenceEvent> action) {
                    if (closed) {
                        return false;
                    }
                    try {
                        if (!iterator.hasNext()) {
                            throw new IllegalStateException(
                                    providerName + " stream ended before its completion event");
                        }
                        InferenceEvent event = decoder.apply(iterator.next());
                        if (event.type() == InferenceEvent.Type.COMPLETE) {
                            close();
                        }
                        action.accept(event);
                        return true;
                    } catch (RuntimeException | Error failure) {
                        closeAfterFailure(failure);
                        throw failure;
                    }
                }

                @Override
                public Spliterator<InferenceEvent> trySplit() {
                    return null;
                }
            };
            return StreamSupport.stream(events, false).onClose(this::close);
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(failure);
            throw failure;
        }
    }

    private void closeAfterFailure(Throwable failure) {
        try {
            close();
        } catch (RuntimeException | Error closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            closeSource.run();
        }
    }
}
