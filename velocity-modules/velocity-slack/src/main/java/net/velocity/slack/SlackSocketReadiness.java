package net.velocity.slack;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.google.gson.JsonParser;
import com.slack.api.socket_mode.SocketModeClient;

/** Observes Slack's protocol handshake without competing with the SDK's ping monitor. */
final class SlackSocketReadiness {

    private volatile CountDownLatch hello = new CountDownLatch(1);

    void reset() {
        hello = new CountDownLatch(1);
    }

    void listenTo(SocketModeClient client) {
        // A delayed message from an old client must not satisfy a later startup.
        CountDownLatch attempt = hello;
        client.addWebSocketMessageListener(message -> {
            // The SDK dispatches parsed JSON messages to these listeners.
            var payload = JsonParser.parseString(message);
            if (payload.isJsonObject()) {
                var type = payload.getAsJsonObject().get("type");
                if (type != null && type.isJsonPrimitive() && "hello".equals(type.getAsString())) {
                    attempt.countDown();
                }
            }
        });
    }

    void await(Duration timeout) throws InterruptedException, TimeoutException {
        if (!hello.await(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
            throw new TimeoutException(
                    "Slack Socket Mode did not receive hello within " + timeout.toSeconds()
                            + " seconds");
        }
    }
}
