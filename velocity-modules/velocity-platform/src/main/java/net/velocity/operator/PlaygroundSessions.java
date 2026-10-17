package net.velocity.operator;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import net.velocity.inference.objects.InferenceContent;
import net.velocity.inference.objects.InferenceMessage;
import net.velocity.inference.objects.InferenceRequest;
import net.velocity.inference.objects.InferenceResponse;
import net.velocity.inference.objects.InferenceToolCalls;

/** Bounded, process-local native history, separate from durable agent execution records. */
final class PlaygroundSessions {

    static final int MAX_SESSIONS = 16;
    static final int MAX_BYTES = 8_388_608;
    static final Duration TTL = Duration.ofMinutes(30);
    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private final Clock clock;

    PlaygroundSessions(Clock clock) {
        this.clock = clock;
    }

    static final class Session {

        final String id = UUID.randomUUID().toString();
        List<InferenceMessage> history = List.of();
        long revision;
        Instant accessed;
        boolean busy;

        Session(Instant accessed) {
            this.accessed = accessed;
        }
    }

    record Turn(Session session, InferenceRequest request, long revision) {}

    record Saved(String sessionId, long revision, boolean continuationAllowed, boolean historyRetained) {}

    synchronized Turn begin(PlaygroundRequests.Request input) {
        Instant now = clock.instant();
        sessions.values().removeIf(session -> !session.busy && session.accessed.plus(TTL).isBefore(now));
        Session session;
        if (input.sessionId() == null) {
            if (sessions.size() >= MAX_SESSIONS) {
                var expired = sessions.values().stream().filter(value -> !value.busy)
                        .min(java.util.Comparator.comparing(value -> value.accessed));
                if (expired.isEmpty()) {
                    throw new ResponseStatusException(
                            HttpStatus.TOO_MANY_REQUESTS,
                            "All playground sessions are busy");
                }
                sessions.remove(expired.get().id);
            }
            session = new Session(now);
            sessions.put(session.id, session);
        } else {
            session = sessions.get(input.sessionId());
            if (session == null) {
                throw new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Playground history expired or was discarded; start a new session");
            }
            if (session.busy || session.revision != input.expectedRevision()) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Playground session changed or is busy");
            }
        }
        var messages = new ArrayList<>(session.history);
        var content = new ArrayList<InferenceContent>(input.images());
        content.add(new InferenceContent.Text(input.prompt()));
        messages.add(new InferenceMessage(InferenceMessage.Role.USER, content));
        var request = new InferenceRequest(messages, input.systemPrompt(), input.options());
        if (bytes(messages) > MAX_BYTES) {
            sessions.remove(session.id);
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "Playground history limit reached; start a new session");
        }
        session.busy = true;
        session.accessed = now;
        return new Turn(session, request, session.revision);
    }

    synchronized Saved finish(Turn turn, InferenceResponse response) {
        Session session = turn.session();
        var history = new ArrayList<>(turn.request().messages());
        history.add(response.toMessage());
        session.revision++;
        boolean pendingAction = response.content().stream().anyMatch(PlaygroundSessions::pendingAction);
        boolean retained = sessions.get(session.id) == session && !pendingAction
                && bytes(history) <= MAX_BYTES;
        if (retained) {
            session.history = List.copyOf(history);
            session.busy = false;
            session.accessed = clock.instant();
        } else {
            sessions.remove(session.id);
        }
        return new Saved(session.id, session.revision, retained, retained);
    }

    synchronized void discard(Turn turn) {
        Session session = sessions.get(turn.session().id);
        // A disconnect after completion must not discard a newer, already admitted turn.
        if (session == turn.session() && (session.revision == turn.revision()
                || session.revision == turn.revision() + 1 && !session.busy)) {
            sessions.remove(session.id);
        }
    }

    synchronized void delete(String id) {
        Session session = sessions.get(id);
        if (session != null && session.busy) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Stop the active request before clearing its history");
        }
        sessions.remove(id);
    }

    synchronized void clear() {
        sessions.clear();
    }

    private static boolean pendingAction(InferenceContent content) {
        var interaction = InferenceToolCalls.decode(content);
        return interaction instanceof InferenceToolCalls.Call
                || interaction instanceof InferenceToolCalls.UnsupportedAction unsupported
                        && unsupported.call();
    }

    private static int bytes(Object value) {
        try {
            return PlaygroundRequests.JSON.writeValueAsBytes(value).length;
        } catch (java.io.IOException invalid) {
            throw new IllegalStateException("Could not measure inference history", invalid);
        }
    }
}
