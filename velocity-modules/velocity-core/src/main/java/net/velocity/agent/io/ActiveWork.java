package net.velocity.agent.io;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import net.velocity.agent.CancellationHandle;

/** Per-owner work tracking under the domain's lock; generation and publication decisions stay in services. */
public final class ActiveWork<C> {

    private final Object lock;
    private final Set<Work<C>> active = new HashSet<>();

    public ActiveWork(Object lock) {
        this.lock = lock;
    }

    public Work<C> register(C context, CancellationHandle caller, Consumer<Work<C>> validate) {
        synchronized (lock) {
            Work<C> work = new Work<>(this, context, caller);
            work.check();
            validate.accept(work);
            active.add(work);
            return work;
        }
    }

    public void cancelAll() {
        synchronized (lock) {
            for (Work<C> work : List.copyOf(active)) {
                if (active.contains(work)) {
                    work.cancellation.cancel();
                    if (work.thread != Thread.currentThread()) {
                        work.thread.interrupt();
                    }
                }
            }
        }
    }

    public static final class Work<C> implements AutoCloseable {

        private final ActiveWork<C> registry;
        private final C context;
        private final CancellationHandle caller;
        private final CancellationHandle cancellation = new CancellationHandle();
        private final Thread thread = Thread.currentThread();

        private Work(ActiveWork<C> registry, C context, CancellationHandle caller) {
            this.registry = registry;
            this.context = context;
            this.caller = caller;
        }

        public C context() {
            return context;
        }

        public CancellationHandle cancellation() {
            return cancellation;
        }

        public void check() {
            cancellation.check();
            if (caller != null) {
                caller.check();
            }
        }

        @Override
        public void close() {
            synchronized (registry.lock) {
                registry.active.remove(this);
            }
        }
    }
}
