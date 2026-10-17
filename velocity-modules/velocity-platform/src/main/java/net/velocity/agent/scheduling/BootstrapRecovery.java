package net.velocity.agent.scheduling;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.springframework.context.SmartLifecycle;

/** Retries local bootstrap until durable recurring registration takes over. */
public final class BootstrapRecovery implements SmartLifecycle {

    private final boolean enabled;
    private final BooleanSupplier register;
    private final Consumer<RuntimeException> failed;
    private final Supplier<ScheduledExecutorService> executors;
    private ScheduledExecutorService bootstrap;
    private volatile boolean running;

    public BootstrapRecovery(
            boolean enabled,
            String threadName,
            BooleanSupplier register,
            Consumer<RuntimeException> failed) {
        this(
                enabled,
                register,
                failed,
                () -> Executors.newSingleThreadScheduledExecutor(
                        Thread.ofPlatform().daemon().name(threadName).factory()));
    }

    BootstrapRecovery(
            boolean enabled,
            BooleanSupplier register,
            Consumer<RuntimeException> failed,
            Supplier<ScheduledExecutorService> executors) {
        this.enabled = enabled;
        this.register = register;
        this.failed = failed;
        this.executors = executors;
    }

    @Override
    public synchronized void start() {
        if (!enabled || running) {
            return;
        }
        running = true;
        bootstrap = executors.get();
        var generation = bootstrap;
        generation.scheduleWithFixedDelay(() -> attempt(generation), 0, 30, TimeUnit.SECONDS);
    }

    private void attempt(ScheduledExecutorService generation) {
        synchronized (this) {
            if (!running || bootstrap != generation || generation.isShutdown()) {
                return;
            }
        }
        try {
            if (register.getAsBoolean()) {
                generation.shutdown();
            }
        } catch (RuntimeException failure) {
            failed.accept(failure);
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (bootstrap != null) {
            bootstrap.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
