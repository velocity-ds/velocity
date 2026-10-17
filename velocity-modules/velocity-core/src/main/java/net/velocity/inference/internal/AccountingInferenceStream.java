package net.velocity.inference.internal;

import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import net.velocity.inference.InferenceStream;
import net.velocity.inference.objects.InferenceEvent;
import net.velocity.inference.objects.InferenceResponse;
import net.velocity.inference.objects.InferenceResponseException;
import net.velocity.usage.UsageLedger;

/** Observes cumulative usage once per request without changing native provider events. */
public final class AccountingInferenceStream implements InferenceStream {

    private final InferenceStream source;
    private final UsageLedger ledger;
    private final UsageLedger.Handle handle;
    private final AtomicBoolean opened = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public AccountingInferenceStream(InferenceStream source, UsageLedger ledger, UsageLedger.Handle handle) {
        this.source = source;
        this.ledger = ledger;
        this.handle = handle;
    }

    @Override
    public Stream<InferenceEvent> events() {
        if (closed.get() || !opened.compareAndSet(false, true)) {
            throw new IllegalStateException(
                    "An inference stream can only be consumed once, before it is closed");
        }
        try {
            var iterator = source.events().iterator();
            var events = new Spliterators.AbstractSpliterator<InferenceEvent>(
                    Long.MAX_VALUE,
                    Spliterator.ORDERED | Spliterator.NONNULL) {

                @Override
                public boolean tryAdvance(Consumer<? super InferenceEvent> action) {
                    if (closed.get()) {
                        return false;
                    }
                    InferenceEvent event;
                    try {
                        if (!iterator.hasNext()) {
                            return false;
                        }
                        event = iterator.next();
                    } catch (RuntimeException | Error failure) {
                        failed(failure);
                        throw failure;
                    }
                    try {
                        if (event.response() != null) {
                            ledger.observe(
                                    handle,
                                    event.response(),
                                    event.type() == InferenceEvent.Type.COMPLETE);
                        }
                        action.accept(event);
                        return true;
                    } catch (RuntimeException | Error failure) {
                        // An event consumer can invoke other providers. Its exceptions are not
                        // evidence about this request and must never replace this request's usage.
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
            failed(failure);
            throw failure;
        }
    }

    private void failed(Throwable failure) {
        try {
            if (failure instanceof InferenceResponseException rejected) {
                ledger.observe(
                        handle,
                        new InferenceResponse(
                                null,
                                rejected.model(),
                                java.util.List.of(),
                                null,
                                rejected.usage(),
                                ""),
                        false);
            }
        } catch (RuntimeException accountingFailure) {
            failure.addSuppressed(accountingFailure);
        }
        closeAfterFailure(failure);
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
        // The saved intent already represents incomplete usage. Closing cannot clear it or
        // downgrade a final observation, including concurrent cancellation after completion.
        if (closed.compareAndSet(false, true)) {
            source.close();
        }
    }
}
