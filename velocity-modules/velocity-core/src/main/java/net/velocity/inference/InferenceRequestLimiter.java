package net.velocity.inference;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

/** FIFO, bounded, interruptible admission. A permit is held until remote work actually returns/closes. */
public final class InferenceRequestLimiter {

    private final InferenceConcurrencyProperties properties;
    private final ArrayDeque<Object> queue = new ArrayDeque<>();
    private int active;

    public InferenceRequestLimiter(InferenceConcurrencyProperties properties) {
        this.properties = Objects.requireNonNull(properties);
    }

    public Permit acquire(Runnable cancellationCheck) {
        Objects.requireNonNull(cancellationCheck);
        long began = System.nanoTime();
        long timeout = InferenceDeadlineScope
                .remainingOr(Duration.ofMillis(properties.maxWaitMillis())).toNanos();
        Object ticket = new Object();
        synchronized (this) {
            check(cancellationCheck);
            if (queue.isEmpty() && active < properties.maxRequests()) {
                active++;
                return new Permit();
            }
            if (queue.size() >= properties.queueCapacity()) {
                throw new InferenceCapacityException("inference_queue_full");
            }
            queue.addLast(ticket);
            try {
                while (true) {
                    check(cancellationCheck);
                    long remaining = timeout - (System.nanoTime() - began);
                    if (remaining <= 0) {
                        throw new InferenceCapacityException("inference_queue_timeout");
                    }
                    if (queue.getFirst() == ticket && active < properties.maxRequests()) {
                        queue.removeFirst();
                        active++;
                        notifyAll();
                        return new Permit();
                    }
                    try {
                        TimeUnit.NANOSECONDS
                                .timedWait(this, Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new CancellationException("Inference queue wait interrupted");
                    }
                }
            } finally {
                queue.remove(ticket);
                notifyAll();
            }
        }
    }

    private static void check(Runnable cancellationCheck) {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Inference request interrupted");
        }
        cancellationCheck.run();
    }

    public record Status(
            int activeRequests,
            int queuedRequests,
            int maxRequests,
            int queueCapacity,
            long maxWaitMillis) {}

    public synchronized Status status() {
        return new Status(
                active,
                queue.size(),
                properties.maxRequests(),
                properties.queueCapacity(),
                properties.maxWaitMillis());
    }

    public final class Permit implements AutoCloseable {

        private boolean released;

        private Permit() {
        }

        @Override
        public void close() {
            synchronized (InferenceRequestLimiter.this) {
                if (released) {
                    return;
                }
                released = true;
                active--;
                InferenceRequestLimiter.this.notifyAll();
            }
        }
    }
}
