package net.velocity.agent.io;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import net.velocity.agent.CancellationHandle;

/** Transport mechanics only; callers retain destinations, authentication, response policy and errors. */
public final class HttpIO {

    private HttpIO() {
    }

    public static HttpResponse.BodySubscriber<byte[]> body(int maximum, String message) {
        return body(maximum, () -> {}, () -> new IOException(message));
    }

    public static HttpResponse.BodySubscriber<byte[]> body(
            int maximum,
            Runnable check,
            Supplier<? extends Throwable> exceeded) {
        return body(Integer.valueOf(maximum), check, exceeded);
    }

    public static HttpResponse.BodySubscriber<byte[]> body(Runnable check) {
        return body(null, check, null);
    }

    private static HttpResponse.BodySubscriber<byte[]> body(
            Integer maximum,
            Runnable check,
            Supplier<? extends Throwable> exceeded) {
        return new CollectedBody(maximum, check, exceeded, Function.identity(), false);
    }

    public static ResponseBody body(
            int maximum,
            Runnable check,
            Supplier<? extends Throwable> exceeded,
            Function<Throwable, Throwable> failure) {
        return new CollectedBody(maximum, check, exceeded, failure, false);
    }

    /** Close after consumption, including request cancellation: returned sensitive bytes are erased on close. */
    public static ResponseBody secureBody(
            int maximum,
            Runnable check,
            Supplier<? extends Throwable> failure) {
        return new CollectedBody(
                maximum,
                check,
                failure,
                error -> failure.get(),
                true);
    }

    public interface ResponseBody extends HttpResponse.BodySubscriber<byte[]>, AutoCloseable {

        @Override
        void close();
    }

    private static final class CollectedBody implements ResponseBody {

        private final Integer maximum;
        private final Runnable check;
        private final Supplier<? extends Throwable> exceeded;
        private final Function<Throwable, Throwable> failure;
        private final boolean sensitive;
        private final ErasingByteArrayOutputStream bytes = new ErasingByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private byte[] delivered;

        private CollectedBody(
                Integer maximum,
                Runnable check,
                Supplier<? extends Throwable> exceeded,
                Function<Throwable, Throwable> failure,
                boolean sensitive) {
            this.maximum = maximum;
            this.check = check;
            this.exceeded = exceeded;
            this.failure = failure;
            this.sensitive = sensitive;
            result.whenComplete((value, error) -> {
                if (result.isCancelled()) {
                    close();
                }
            });
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public synchronized void onSubscribe(Flow.Subscription value) {
            if (subscription != null || result.isDone()) {
                value.cancel();
                return;
            }
            subscription = value;
            value.request(1);
        }

        @Override
        public synchronized void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) {
                return;
            }
            try {
                check.run();
                for (ByteBuffer buffer : buffers) {
                    if (maximum != null && (long) bytes.size() + buffer.remaining() > maximum) {
                        fail(exceeded.get());
                        return;
                    }
                    byte[] chunk = new byte[buffer.remaining()];
                    try {
                        buffer.get(chunk);
                        bytes.writeBytes(chunk);
                    } finally {
                        if (sensitive) {
                            Arrays.fill(chunk, (byte) 0);
                        }
                    }
                }
                subscription.request(1);
            } catch (RuntimeException invalid) {
                fail(failure.apply(invalid));
            }
        }

        @Override
        public synchronized void onError(Throwable error) {
            if (!result.isDone()) {
                fail(failure.apply(error));
            }
        }

        private void fail(Throwable error) {
            bytes.close();
            result.completeExceptionally(error);
            if (subscription != null) {
                subscription.cancel();
            }
        }

        @Override
        public synchronized void onComplete() {
            if (result.isDone()) {
                return;
            }
            byte[] complete = bytes.toByteArray();
            bytes.close();
            if (sensitive) {
                delivered = complete;
            }
            if (!result.complete(complete)) {
                Arrays.fill(complete, (byte) 0);
            }
        }

        @Override
        public synchronized void close() {
            bytes.close();
            if (delivered != null) {
                Arrays.fill(delivered, (byte) 0);
                delivered = null;
            }
            if (!result.isDone()) {
                result.cancel(false);
            }
            if (subscription != null) {
                subscription.cancel();
            }
        }
    }

    public static <T> T await(CompletableFuture<T> pending, CancellationHandle cancellation, long deadline)
            throws InterruptedException, ExecutionException, TimeoutException {
        return await(pending, cancellation, deadline, System::nanoTime);
    }

    public static <T> T await(
            CompletableFuture<T> pending,
            CancellationHandle cancellation,
            long deadline,
            LongSupplier nanoTime) throws InterruptedException, ExecutionException, TimeoutException {
        try {
            while (true) {
                cancellation.check();
                long left = deadline - nanoTime.getAsLong();
                if (left <= 0) {
                    throw new TimeoutException();
                }
                try {
                    return pending
                            .get(Math.min(left, TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS);
                } catch (TimeoutException poll) {
                    /* Check cancellation and the shared deadline before waiting again. */ }
            }
        } catch (ExecutionException failure) {
            cancellation.check();
            throw failure;
        } finally {
            pending.cancel(true);
        }
    }

    public static Instant retryAt(String value, Clock clock) {
        if (value == null || value.length() > 128) {
            return null;
        }
        try {
            long seconds = Long.parseLong(value);
            return seconds >= 0 && seconds <= 604800 ? clock.instant().plusSeconds(seconds) : null;
        } catch (RuntimeException notSeconds) {
            try {
                Instant instant = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant();
                return instant.isAfter(clock.instant())
                        && instant.isBefore(clock.instant().plusSeconds(604800)) ? instant : null;
            } catch (RuntimeException invalid) {
                return null;
            }
        }
    }
}
