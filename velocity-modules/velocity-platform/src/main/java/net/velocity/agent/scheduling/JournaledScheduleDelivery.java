package net.velocity.agent.scheduling;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import net.velocity.agent.delivery.DeliveryJournal;
import net.velocity.agent.delivery.DeliveryTransport;
import net.velocity.agent.delivery.DeliveryTransport.Destination;
import net.velocity.agent.delivery.DeliveryTransport.Operation;
import net.velocity.agent.delivery.DeliveryTransport.Receipt;
import net.velocity.agent.state.TrustedScope;
import net.velocity.util.Sha256;

/** One post per occurrence, using the existing delivery intent/acknowledgement journal. */
public final class JournaledScheduleDelivery implements ScheduleDelivery {

    private final DeliveryJournal journal;
    private final DeliveryTransport transport;
    private final BiFunction<TrustedScope, ScheduleDestination, Destination> destinationResolver;
    private final Consumer<Destination> accessVerifier;
    private final Function<String, String> blocksRenderer;
    private final Object[] locks = new Object[64];

    public JournaledScheduleDelivery(
            DeliveryJournal journal,
            DeliveryTransport transport,
            BiFunction<TrustedScope, ScheduleDestination, Destination> destinationResolver,
            Consumer<Destination> accessVerifier,
            Function<String, String> blocksRenderer) {
        this.journal = Objects.requireNonNull(journal);
        this.transport = Objects.requireNonNull(transport);
        this.destinationResolver = Objects.requireNonNull(destinationResolver);
        this.accessVerifier = Objects.requireNonNull(accessVerifier);
        this.blocksRenderer = Objects.requireNonNull(blocksRenderer);
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new Object();
        }
    }

    @Override
    public void verify(TrustedScope owner, ScheduleDestination destination) {
        owner.requireOwnerAccountId();
        accessVerifier.accept(destinationResolver.apply(owner, destination));
    }

    @Override
    public DeliveryResult deliver(
            String occurrenceId,
            TrustedScope owner,
            ScheduleDestination destination,
            String text) {
        return deliver(occurrenceId, owner, destination, text, () -> blocksRenderer.apply(text));
    }

    /** Render only a fresh intent; recovery always reuses the exact saved blocks. */
    public DeliveryResult deliver(
            String occurrenceId,
            TrustedScope owner,
            ScheduleDestination destination,
            String text,
            Supplier<String> blocks) {
        return deliver(occurrenceId, owner, destination, text, true, blocks);
    }

    @Override
    public DeliveryResult reconcile(
            String occurrenceId,
            TrustedScope owner,
            ScheduleDestination destination,
            String text) {
        return deliver(occurrenceId, owner, destination, text, false, () -> blocksRenderer.apply(text));
    }

    private DeliveryResult deliver(
            String occurrenceId,
            TrustedScope owner,
            ScheduleDestination destination,
            String text,
            boolean allowSend,
            Supplier<String> blocks) {
        if (occurrenceId == null || occurrenceId.isBlank()) {
            throw new IllegalArgumentException("Occurrence ID is required");
        }
        String ownerAccountId = owner.requireOwnerAccountId();
        validateText(destination, text);
        Destination resolved = destinationResolver.apply(owner, destination);
        String id = "schedule_" + UUID.nameUUIDFromBytes(occurrenceId.getBytes(StandardCharsets.UTF_8));
        synchronized (locks[Math.floorMod(id.hashCode(), locks.length)]) {
            var state = journal.read(id);
            var turn = state.turns.computeIfAbsent(1L, ignored -> {
                var value = new DeliveryJournal.Turn();
                value.number = 1;
                value.ownerAccountId = ownerAccountId;
                value.destination = resolved;
                value.answerDigest = digest(text);
                return value;
            });
            if (!ownerAccountId.equals(turn.ownerAccountId)
                    || !resolved.equals(turn.destination) || !digest(text).equals(turn.answerDigest)) {
                throw new IllegalStateException(
                        "Scheduled delivery identity or payload changed within an occurrence");
            }
            try {
                accessVerifier.accept(resolved);
            } catch (RuntimeException denied) {
                if (turn.completed) {
                    return outcome(turn);
                }
                if (turn.pending != null) {
                    return uncertain(state, turn, "Cannot verify destination access to reconcile delivery");
                }
                return failed(state, turn, "Destination access was not verified");
            }
            if (turn.completed) {
                return outcome(turn);
            }
            if (turn.pending != null) {
                return reconcile(state, turn);
            }
            if (!allowSend) {
                return failed(state, turn, "Occurrence no longer authorizes a new delivery");
            }

            var operation = new Operation(
                    id + "_post",
                    id,
                    1,
                    resolved,
                    "post",
                    null,
                    text,
                    blocks.get(),
                    turn.answerDigest,
                    text.length());
            turn.pending = operation;
            journal.save(state); // Intent must be durable before making any remote write.
            try {
                acknowledge(state, turn, transport.execute(operation));
                return outcome(turn);
            } catch (Exception failure) {
                // A receipt save may itself fail. Preserve the original intent and any positive receipt.
                turn.pending = operation;
                turn.completed = false;
                if (failure instanceof DeliveryTransport.Failure rejection
                        && rejection.acknowledgedAbsent()) {
                    return failed(state, turn, "Transport rejected the message without sending it");
                }
                return uncertain(
                        state,
                        turn,
                        "Delivery was not acknowledged; inspection is required before any further action");
            }
        }
    }

    private DeliveryResult reconcile(DeliveryJournal.State state, DeliveryJournal.Turn turn) {
        Operation pending = turn.pending;
        try {
            var saved = turn.receipts.stream().filter(receipt -> pending.id().equals(receipt.operationId()))
                    .findFirst();
            var receipt = saved.isPresent() ? saved : transport.inspect(pending);
            if (receipt.isPresent()) {
                acknowledge(state, turn, receipt.get());
                return outcome(turn);
            }
        } catch (Exception ignored) {
            /* Missing evidence is never permission to repeat a write. */ }
        turn.pending = pending;
        turn.completed = false;
        return uncertain(state, turn, "Delivery could not be positively reconciled; message was not resent");
    }

    private void acknowledge(DeliveryJournal.State state, DeliveryJournal.Turn turn, Receipt receipt) {
        if (receipt == null || !turn.pending.id().equals(receipt.operationId())
                || receipt.remoteId() == null || receipt.remoteId().isBlank()) {
            throw new IllegalStateException("Missing delivery acknowledgement");
        }
        if (turn.receipts.stream().noneMatch(saved -> saved.operationId().equals(receipt.operationId()))) {
            turn.receipts.add(receipt);
        }
        turn.cardId = receipt.remoteId();
        turn.pending = null;
        turn.completed = true;
        turn.uncertain = false;
        turn.failure = null;
        journal.save(state);
    }

    private DeliveryResult failed(DeliveryJournal.State state, DeliveryJournal.Turn turn, String failure) {
        turn.pending = null;
        turn.completed = true;
        turn.uncertain = false;
        turn.failure = failure;
        journal.save(state);
        return outcome(turn);
    }

    private DeliveryResult uncertain(DeliveryJournal.State state, DeliveryJournal.Turn turn, String failure) {
        turn.uncertain = true;
        turn.failure = failure;
        try {
            journal.save(state);
        } catch (RuntimeException ignored) {
            // The write-ahead intent already exists. A failed acknowledgement save cannot permit resend.
        }
        return new DeliveryResult(State.uncertain, null, failure);
    }

    private static DeliveryResult outcome(DeliveryJournal.Turn turn) {
        if (turn.uncertain) {
            return new DeliveryResult(State.uncertain, null, turn.failure);
        }
        if (turn.failure != null) {
            return new DeliveryResult(State.failed, null, turn.failure);
        }
        return new DeliveryResult(State.succeeded, turn.cardId, null);
    }

    private static String digest(String value) {
        return Sha256.hex(value);
    }
}
