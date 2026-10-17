package net.velocity.agent;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import net.velocity.inference.InferenceDeadlineScope;

/** Cooperative cancellation; an interrupted external operation may still have effects. */
public final class CancellationHandle implements AutoCloseable {

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final ThreadLocal<Integer> operationDepth = ThreadLocal.withInitial(() -> 0);
    private final Map<Future<?>, Operation> futures = new ConcurrentHashMap<>();

    public boolean cancelled() {
        return cancelled.get();
    }

    public boolean settled() {
        return inFlight.get() == 0;
    }

    /** Internal nested loops exclude their own still-running ancestor operation. */
    boolean settledForCaller() {
        return inFlight.get() == operationDepth.get();
    }

    public void check() {
        if (cancelled() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException();
        }
    }

    public void cancel() {
        cancelled.set(true);
        futures.forEach((future, operation) -> {
            future.cancel(true);
            operation.cancelBeforeStart();
        });
    }

    public <T> T await(ExecutorService executor, Callable<T> action, long timeoutMillis) throws Exception {
        check();
        // Register before scheduling: neither queued work nor an interrupt-ignoring action may
        // make the handle appear settled while an external effect can still begin or finish.
        var operation = new Operation();
        inFlight.incrementAndGet();
        Future<T> future;
        int ancestors = operationDepth.get();
        long deadline = InferenceDeadlineScope.captureDeadline(timeoutMillis);
        try {
            future = executor.submit(() -> {
                if (!operation.start()) {
                    throw new CancellationException();
                }
                operationDepth.set(ancestors + 1);
                try (var deadlineScope = InferenceDeadlineScope.open(deadline)) {
                    check();
                    if (deadline - System.nanoTime() <= 0) {
                        throw new TimeoutException("Operation deadline expired before dispatch");
                    }
                    return action.call();
                } finally {
                    operationDepth.remove();
                    operation.finish();
                }
            });
        } catch (RuntimeException rejected) {
            operation.cancelBeforeStart();
            throw rejected;
        }
        futures.put(future, operation);
        try {
            check();
            return future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw new IllegalStateException("External operation failed");
        } finally {
            future.cancel(true);
            operation.cancelBeforeStart();
            futures.remove(future);
        }
    }

    private final class Operation {

        // 0 = queued, 1 = actually running, 2 = settled. Cancellation of a running Future
        // never decrements inFlight; only the action's finally block can settle it.
        private final AtomicInteger state = new AtomicInteger();

        boolean start() {
            return state.compareAndSet(0, 1);
        }

        void cancelBeforeStart() {
            if (state.compareAndSet(0, 2)) {
                inFlight.decrementAndGet();
            }
        }

        void finish() {
            if (state.compareAndSet(1, 2)) {
                inFlight.decrementAndGet();
            }
        }
    }

    @Override
    public void close() {
        cancel();
    }
}
