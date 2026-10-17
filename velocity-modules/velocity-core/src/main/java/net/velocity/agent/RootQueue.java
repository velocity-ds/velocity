package net.velocity.agent;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.velocity.agent.config.AgentConcurrencyProperties;
import net.velocity.agent.persistence.ExecutionRepository;
import net.velocity.agent.state.ExecutionSnapshot;
import net.velocity.agent.state.ExecutionStatus;

/**
 * The durable FIFO of admitted root tasks and the virtual-thread workers that run them. All entry points share
 * this queue; children execute inside their root's slot. Queue time counts against the task's active-time
 * allowance, and queued work past its deadline stops as partial without a worker.
 *
 * <p>Every method that reads or changes queue state runs while holding {@code lock}, the gateway monitor that
 * also serializes admission and control. The gateway's public methods are {@code synchronized}; the coordinator
 * and worker completion take the same lock here. The gateway supplies the decisions this class cannot make
 * itself through {@link Runtime}.
 */
final class RootQueue {

    private static final Logger log = LoggerFactory.getLogger(RootQueue.class);

    /** Gateway decisions and actions the queue needs while launching and finishing work. */
    interface Runtime {

        /**
         * Verifies the saved authority and pinned inference binding of a task about to start. Returns false
         * after blocking the task when it cannot run.
         */
        boolean admissible(ExecutionSnapshot authority);

        /**
         * Called under the lock just before a worker starts; false means the task was blocked and no worker
         * runs.
         */
        boolean starting(ExecutionSnapshot admitted);

        /** The worker body, on the worker's own thread. */
        void run(String id, CancellationHandle cancellation);

        /** After the worker body returns, on the worker thread, before the queue records completion. */
        void finished(ExecutionSnapshot admitted);

        /** A queued task's active-time allowance expired before a worker was free. */
        void expired(String id);
    }

    private final Object lock;
    private final ExecutionRepository repository;
    private final AgentConcurrencyProperties concurrency;
    private final Runtime runtime;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Worker> active = new HashMap<>();
    private final LinkedHashSet<String> queued = new LinkedHashSet<>();
    private final Map<String, Instant> queueDeadlines = new HashMap<>();
    private final ScheduledExecutorService coordinator = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("agent-root-queue").factory());
    private final List<Predicate<String>> settlementChecks = new CopyOnWriteArrayList<>();
    private long queueSequence;
    private boolean queueSequenceInitialized;
    private boolean recovering;
    private boolean closing;

    RootQueue(
            Object lock,
            ExecutionRepository repository,
            AgentConcurrencyProperties concurrency,
            Runtime runtime) {
        this.lock = Objects.requireNonNull(lock);
        this.repository = Objects.requireNonNull(repository);
        this.concurrency = Objects.requireNonNull(concurrency);
        this.runtime = Objects.requireNonNull(runtime);
        // Unsynchronized size reads are fine for a sampled gauge and never contend with the queue lock.
        AgentMetrics.gauge("velocity.tasks.active", active, Map::size);
        AgentMetrics.gauge("velocity.tasks.queued", queued, LinkedHashSet::size);
    }

    /** Starts the coordinator once the gateway that owns the lock is fully constructed. */
    void start() {
        coordinator.scheduleWithFixedDelay(this::coordinate, 50, 50, TimeUnit.MILLISECONDS);
    }

    /** True once shutdown began draining workers; admission is refused from then on. */
    boolean closing() {
        return closing;
    }

    /** Startup recovery relaunches saved work with its persisted order and defers draining until it ends. */
    void beginRecovery() {
        recovering = true;
    }

    void endRecovery() {
        recovering = false;
    }

    /** Recovery seeds the sequence from the saved snapshots before any new admission. */
    void initializeSequence(long highest) {
        queueSequence = Math.max(queueSequence, highest);
        queueSequenceInitialized = true;
    }

    long nextQueueSequence() {
        if (!queueSequenceInitialized) {
            queueSequence = Math.max(
                    queueSequence,
                    repository.list().stream().mapToLong(s -> s.queueSequence).max().orElse(0));
            queueSequenceInitialized = true;
        }
        return ++queueSequence;
    }

    void requireQueueCapacity(String id) {
        if (id != null && (active.containsKey(id) || queued.contains(id))) {
            return;
        }
        if ((long) active.size() + queued.size() >= (long) concurrency.maxActiveRoots()
                + concurrency.queueCapacity()) {
            throw new AdmissionCapacityException();
        }
    }

    /** Enqueues a running task (once) and, outside recovery, starts workers while slots are free. */
    void launch(String id) {
        if (closing) {
            return;
        }
        var authority = repository.get(id);
        var previous = active.get(id);
        if (previous != null && (!previous.finished || !workerSettled(id, previous))) {
            return;
        }
        if (previous != null) {
            active.remove(id, previous);
        }
        if (authority.status != ExecutionStatus.running || authority.cancellationRequested) {
            return;
        }
        if (!queued.contains(id)) {
            // Already-admitted work is never discarded when a later configuration reduces capacity.
            // Public admission/control methods check capacity before committing new work.
            if (authority.queuedAt == null) {
                long sequence = recovering ? authority.queueSequence : nextQueueSequence();
                authority = repository.update(id, -1, s -> {
                    s.queueSequence = sequence;
                    s.queuedAt = Instant.now();
                });
            }
            queued.add(id);
            queueDeadlines.put(
                    id,
                    authority.queuedAt.plusMillis(
                            Math.max(0, authority.limits.activeMillis() - authority.activeMillis)));
        }
        if (!recovering) {
            drainQueue();
        }
    }

    private void coordinate() {
        synchronized (lock) {
            if (closing || recovering) {
                return;
            }
            try {
                expireQueued();
                for (var entry : List.copyOf(active.entrySet())) {
                    if (entry.getValue().finished && workerSettled(entry.getKey(), entry.getValue())) {
                        active.remove(entry.getKey(), entry.getValue());
                        if (repository.get(entry.getKey()).status == ExecutionStatus.running) {
                            launch(entry.getKey());
                        }
                    }
                }
                drainQueue();
            } catch (RuntimeException failure) {
                log.warn(
                        "stage=root_queue outcome=coordination_pending exception={}",
                        failure.getClass().getSimpleName());
            }
        }
    }

    void drainQueue() {
        expireQueued();
        while (!closing && active.size() < concurrency.maxActiveRoots() && !queued.isEmpty()) {
            String id = queued.iterator().next();
            var authority = repository.get(id);
            if (authority.status != ExecutionStatus.running || authority.cancellationRequested) {
                queued.remove(id);
                queueDeadlines.remove(id);
                if (authority.queuedAt != null) {
                    repository.update(id, -1, s -> s.queuedAt = null);
                }
                continue;
            }
            if (!runtime.admissible(authority)) {
                queued.remove(id);
                queueDeadlines.remove(id);
                continue;
            }
            repository.update(id, -1, RootQueue::chargeQueueWait);
            queued.remove(id);
            queueDeadlines.remove(id);
            startWorker(id);
        }
    }

    private void expireQueued() {
        Instant now = Instant.now();
        for (var entry : List.copyOf(queueDeadlines.entrySet())) {
            if (now.isBefore(entry.getValue())) {
                continue;
            }
            String id = entry.getKey();
            runtime.expired(id);
            repository.update(id, -1, s -> {
                chargeQueueWait(s);
                if (!s.status.terminal()) {
                    s.status = s.cancellationRequested ? ExecutionStatus.cancelled : ExecutionStatus.partial;
                    s.failure = "active_time_limit";
                    s.finalAnswer = "Work stopped while waiting for installation capacity because the task time allowance expired. Saved evidence remains available.";
                    s.pendingQuestions.clear();
                }
            });
            queued.remove(id);
            queueDeadlines.remove(id);
        }
    }

    /** Moves the time spent waiting for a slot onto the task's active-time account. */
    static void chargeQueueWait(ExecutionSnapshot state) {
        if (state.queuedAt == null) {
            return;
        }
        long remaining = Math.max(0, state.limits.activeMillis() - state.activeMillis);
        long elapsed = Math.max(0, java.time.Duration.between(state.queuedAt, Instant.now()).toMillis());
        state.activeMillis += Math.min(remaining, elapsed);
        state.queuedAt = null;
    }

    AgentGateway.ConcurrencyStatus concurrencyStatus() {
        return new AgentGateway.ConcurrencyStatus(
                active.size(),
                queued.size(),
                concurrency.maxActiveRoots(),
                concurrency.queueCapacity());
    }

    private void startWorker(String id) {
        var worker = new Worker();
        active.put(id, worker);
        var admitted = repository.get(id);
        if (!runtime.starting(admitted)) {
            active.remove(id, worker);
            return;
        }
        workers.execute(() -> {
            try {
                runtime.run(id, worker.cancellation);
            } finally {
                runtime.finished(admitted);
                synchronized (lock) {
                    worker.finished = true;
                    if (workerSettled(id, worker)) {
                        active.remove(id, worker);
                    }
                    // Input can race a worker's transition to waiting. Pick up the persisted
                    // resume.
                    if (!closing && repository.get(id).status == ExecutionStatus.running
                            && workerSettled(id, worker)) {
                        launch(id);
                    }
                    if (!closing) {
                        drainQueue();
                    }
                }
            }
        });
    }

    /** Removes the task from the queue and signals a live worker; false when no worker is running it. */
    boolean interrupt(String id) {
        queued.remove(id);
        queueDeadlines.remove(id);
        var worker = active.get(id);
        if (worker != null && !worker.finished) {
            worker.cancellation.cancel();
            return true;
        }
        return false;
    }

    /** Drops every trace of a deleted execution. */
    void forget(String id) {
        active.remove(id);
        queued.remove(id);
        queueDeadlines.remove(id);
    }

    boolean settled(String id) {
        var worker = active.get(id);
        return (worker == null || worker.finished && worker.cancellation.settled())
                && externallySettled(id);
    }

    private boolean workerSettled(String id, Worker worker) {
        return worker.cancellation.settled() && externallySettled(id);
    }

    boolean externallySettled(String id) {
        try {
            return settlementChecks.stream().allMatch(check -> check.test(id));
        } catch (RuntimeException failure) {
            log.debug(
                    "executionId={} stage=external_settlement outcome=unknown exception={}",
                    id,
                    failure.getClass().getSimpleName());
            return false;
        }
    }

    /** A returned handler does not imply a remote process has stopped. Delivery and deletion use this fence. */
    void registerSettlementCheck(Predicate<String> check) {
        settlementChecks.add(Objects.requireNonNull(check));
    }

    /**
     * Stops the coordinator, records cancellation for every live worker and refuses further launches. Returns
     * false when shutdown had already begun. Runs under the lock.
     */
    boolean beginClose() {
        if (closing) {
            return false;
        }
        closing = true;
        coordinator.shutdownNow();
        for (var entry : active.entrySet()) {
            if (!entry.getValue().finished) {
                repository.update(entry.getKey(), -1, s -> s.cancellationRequested = true);
                entry.getValue().cancellation.cancel();
            }
        }
        workers.shutdown();
        return true;
    }

    /** Waits for the signalled workers to drain; runs outside the lock. */
    void awaitWorkers() {
        try {
            if (!workers.awaitTermination(30, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static final class Worker {

        final CancellationHandle cancellation = new CancellationHandle();
        boolean finished;
    }
}
