package net.velocity.slack;

import java.time.Duration;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.ApplicationContextException;
import org.springframework.context.SmartLifecycle;

import com.slack.api.bolt.jakarta_socket_mode.SocketModeApp;
import com.slack.api.socket_mode.SocketModeClient;

/** Starts with Spring and releases the socket, including the SDK's reconnection workers. */
public final class SlackSocketConnection implements SmartLifecycle, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SlackSocketConnection.class);
    private final SocketModeApp socketModeApp;
    private final SlackSocketReadiness readiness;
    private final Duration startupTimeout;
    private volatile boolean running;
    private boolean releaseRequired;

    SlackSocketConnection(SocketModeApp socketModeApp, SlackSocketReadiness readiness) {
        this(socketModeApp, readiness, Duration.ofSeconds(20));
    }

    SlackSocketConnection(
            SocketModeApp socketModeApp,
            SlackSocketReadiness readiness,
            Duration startupTimeout) {
        this.socketModeApp = Objects.requireNonNull(socketModeApp);
        this.readiness = Objects.requireNonNull(readiness);
        this.startupTimeout = Objects.requireNonNull(startupTimeout);
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        try {
            releaseRequired = true;
            readiness.reset();
            socketModeApp.startAsync();
            if (socketModeApp.getClient() == null) {
                throw new IllegalStateException("Slack Socket Mode did not create a client");
            }
            // verifyConnection() consumes shared pong state and can race the SDK's
            // session handover. The server's hello confirms protocol readiness instead.
            readiness.await(startupTimeout);
            running = true;
            log.info("Slack Socket Mode connected");
        } catch (Exception failure) {
            releaseConnection();
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ApplicationContextException(
                    "Could not start Slack Socket Mode. Check tokens and connectivity.",
                    failure);
        }
    }

    @Override
    public synchronized void stop() {
        if (releaseRequired) {
            running = false;
            releaseConnection();
            log.info("Slack Socket Mode stopped");
        }
    }

    private void releaseConnection() {
        releaseRequired = false;
        // SocketModeApp.close() clears its reference without closing the underlying client.
        SocketModeClient client = socketModeApp.getClient();
        try {
            if (client != null) {
                client.setAutoReconnectEnabled(false);
            }
            socketModeApp.close();
        } catch (Exception failure) {
            log.warn("Could not stop Slack Socket Mode adapter ({})", failure.getClass().getSimpleName());
        } finally {
            if (client != null) {
                try {
                    client.close();
                } catch (Exception failure) {
                    log.warn(
                            "Could not close Slack WebSocket client ({})",
                            failure.getClass().getSimpleName());
                }
            }
        }
    }

    @Override
    public void destroy() {
        stop();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // Stop accepting events before Spring shuts down the ingress worker pool.
        return Integer.MAX_VALUE;
    }
}
