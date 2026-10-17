package net.velocity.slack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

import com.slack.api.util.thread.DaemonThreadExecutorServiceProvider;
import com.slack.api.util.thread.ExecutorServiceProvider;

/** Owns SDK pools, including Bolt and Tyrus pools their close methods do not release. */
final class SlackSdkExecutors implements ExecutorServiceProvider, AutoCloseable {

    private final ExecutorServiceProvider delegate = DaemonThreadExecutorServiceProvider.getInstance();
    private final List<ExecutorService> executors = new ArrayList<>();
    private boolean closed;

    @Override
    public synchronized ExecutorService createThreadPoolExecutor(String threadGroupName, int poolSize) {
        requireOpen();
        var executor = delegate.createThreadPoolExecutor(threadGroupName, poolSize);
        executors.add(executor);
        return executor;
    }

    @Override
    public synchronized ScheduledExecutorService createThreadScheduledExecutor(String threadGroupName) {
        requireOpen();
        var executor = delegate.createThreadScheduledExecutor(threadGroupName);
        executors.add(executor);
        return executor;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Slack SDK executors have been closed");
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        // Spring destroys the message executor and Slack client before this provider.
        for (ExecutorService executor : executors) {
            executor.shutdownNow();
        }
        executors.clear();
    }
}
