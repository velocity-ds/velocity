package net.velocity.inference;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InferenceRequestLimiterTest {

    private static final Runnable NEVER_CANCELLED = () -> {};

    @Test
    void permitsUpToTheMaximumAndRefusesWhenTheQueueIsFull() {
        var limiter = new InferenceRequestLimiter(new InferenceConcurrencyProperties(2, 0, 1_000));
        var first = limiter.acquire(NEVER_CANCELLED);
        var second = limiter.acquire(NEVER_CANCELLED);
        assertThat(limiter.status()).isEqualTo(new InferenceRequestLimiter.Status(2, 0, 2, 0, 1_000));
        assertThatThrownBy(() -> limiter.acquire(NEVER_CANCELLED))
                .isInstanceOfSatisfying(
                        InferenceCapacityException.class,
                        failure -> assertThat(failure.code()).isEqualTo("inference_queue_full"));

        first.close();
        first.close();
        assertThat(limiter.status().activeRequests()).isEqualTo(1);
        var third = limiter.acquire(NEVER_CANCELLED);
        assertThat(limiter.status().activeRequests()).isEqualTo(2);
        second.close();
        third.close();
        assertThat(limiter.status().activeRequests()).isZero();
    }

    @Test
    void queuedRequestsTimeOutWithTheirOwnCode() {
        var limiter = new InferenceRequestLimiter(new InferenceConcurrencyProperties(1, 1, 100));
        try (var held = limiter.acquire(NEVER_CANCELLED)) {
            assertThatThrownBy(() -> limiter.acquire(NEVER_CANCELLED))
                    .isInstanceOfSatisfying(
                            InferenceCapacityException.class,
                            failure -> assertThat(failure.code()).isEqualTo("inference_queue_timeout"));
            assertThat(limiter.status().queuedRequests()).isZero();
        }
    }

    @Test
    void releasingAPermitHandsItToTheWaiterInOrder() throws Exception {
        var limiter = new InferenceRequestLimiter(new InferenceConcurrencyProperties(1, 1, 5_000));
        var held = limiter.acquire(NEVER_CANCELLED);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<InferenceRequestLimiter.Permit> waiting = executor
                    .submit(() -> limiter.acquire(NEVER_CANCELLED));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (limiter.status().queuedRequests() == 0 && System.nanoTime() < until) {
                Thread.sleep(5);
            }
            assertThat(limiter.status().queuedRequests()).isEqualTo(1);
            assertThatThrownBy(() -> limiter.acquire(NEVER_CANCELLED))
                    .isInstanceOfSatisfying(
                            InferenceCapacityException.class,
                            failure -> assertThat(failure.code()).isEqualTo("inference_queue_full"));

            held.close();
            try (var granted = waiting.get(5, TimeUnit.SECONDS)) {
                assertThat(granted).isNotNull();
                assertThat(limiter.status()).isEqualTo(new InferenceRequestLimiter.Status(1, 0, 1, 1, 5_000));
            }
            assertThat(limiter.status().activeRequests()).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cancellationChecksAbortImmediatelyAndWhileQueued() {
        var limiter = new InferenceRequestLimiter(new InferenceConcurrencyProperties(1, 1, 5_000));
        assertThatThrownBy(() -> limiter.acquire(() -> {
            throw new CancellationException("stop");
        })).isInstanceOf(CancellationException.class);
        assertThat(limiter.status().activeRequests()).isZero();

        try (var held = limiter.acquire(NEVER_CANCELLED)) {
            var checks = new AtomicInteger();
            assertThatThrownBy(() -> limiter.acquire(() -> {
                if (checks.incrementAndGet() > 1) {
                    throw new CancellationException("stop while queued");
                }
            })).isInstanceOf(CancellationException.class).hasMessage("stop while queued");
            assertThat(limiter.status().queuedRequests()).isZero();
        }
    }

    @Test
    void anInterruptedCallerIsCancelledAndKeepsItsInterruptFlag() {
        var limiter = new InferenceRequestLimiter(InferenceConcurrencyProperties.defaults());
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> limiter.acquire(NEVER_CANCELLED))
                    .isInstanceOf(CancellationException.class)
                    .hasMessage("Inference request interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void concurrencyPropertiesAreValidated() {
        assertThatThrownBy(() -> new InferenceConcurrencyProperties(0, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InferenceConcurrencyProperties(1, -1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InferenceConcurrencyProperties(1, 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(InferenceConcurrencyProperties.defaults())
                .isEqualTo(new InferenceConcurrencyProperties(4, 100, 30_000));
    }
}
