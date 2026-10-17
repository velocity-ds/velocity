package net.velocity.agent.slack;

import java.util.Objects;

import net.velocity.agent.delivery.DeliveryJournal;
import net.velocity.agent.delivery.DeliveryTransport;
import net.velocity.agent.scheduling.JournaledScheduleDelivery;
import net.velocity.agent.scheduling.ScheduleDelivery;
import net.velocity.agent.scheduling.ScheduleDestination;
import net.velocity.agent.state.ExecutionSnapshot;
import net.velocity.agent.state.ExecutionStatus;
import net.velocity.agent.state.TrustedScope;
import net.velocity.usage.UsageReports;

/** Explicitly bridges a saved Slack owner's identity to an authorized channel and optional thread. */
public final class SlackScheduleDelivery implements ScheduleDelivery {

    private final JournaledScheduleDelivery delegate;
    private final DeliveryUsageFooter usageFooter;

    public SlackScheduleDelivery(
            DeliveryJournal journal,
            DeliveryTransport transport,
            SlackAccessVerifier access,
            UsageReports usageReports) {
        Objects.requireNonNull(access);
        usageFooter = new DeliveryUsageFooter(usageReports);
        delegate = new JournaledScheduleDelivery(
                journal,
                transport,
                SlackScheduleDelivery::resolve,
                destination -> access.verify(destination.scope()),
                SlackTableBlocks::render);
    }

    @Override
    public void verify(TrustedScope owner, ScheduleDestination destination) {
        delegate.verify(owner, destination);
    }

    @Override
    public void validateText(ScheduleDestination destination, String text) {
        ScheduleDelivery.super.validateText(destination, text);
        // Slack truncates longer messages. UTF-16 length is a conservative bound for supplementary characters.
        if (text.length() > 40_000) {
            throw new IllegalArgumentException("Scheduled Slack text must not exceed 40000 characters");
        }
    }

    @Override
    public DeliveryResult deliver(
            String occurrenceId,
            TrustedScope owner,
            ScheduleDestination destination,
            String text) {
        try {
            validateText(destination, text);
        } catch (IllegalArgumentException failure) {
            return new DeliveryResult(State.failed, null, failure.getMessage());
        }
        return delegate.deliver(occurrenceId, owner, destination, text);
    }

    @Override
    public DeliveryResult reconcile(
            String occurrenceId,
            TrustedScope owner,
            ScheduleDestination destination,
            String text) {
        try {
            validateText(destination, text);
        } catch (IllegalArgumentException failure) {
            return new DeliveryResult(State.failed, null, failure.getMessage());
        }
        return delegate.reconcile(occurrenceId, owner, destination, text);
    }

    @Override
    public DeliveryResult deliverExecution(
            String occurrenceId,
            TrustedScope owner,
            ScheduleDestination destination,
            ExecutionSnapshot execution) {
        if (!owner.requireOwnerAccountId().equals(execution.scope.requireOwnerAccountId())) {
            throw new SecurityException("Scheduled execution owner does not match delivery owner");
        }
        if (execution.status != ExecutionStatus.succeeded || execution.cancellationRequested) {
            return new DeliveryResult(State.failed, null, "Scheduled execution has not succeeded");
        }
        try {
            validateText(destination, execution.finalAnswer);
        } catch (IllegalArgumentException failure) {
            return new DeliveryResult(State.failed, null, failure.getMessage());
        }
        return delegate.deliver(
                occurrenceId,
                owner,
                destination,
                execution.finalAnswer,
                () -> SlackTableBlocks.render(execution.finalAnswer, usageFooter.render(execution)));
    }

    private static DeliveryTransport.Destination resolve(
            TrustedScope owner,
            ScheduleDestination destination) {
        Objects.requireNonNull(owner, "Schedule owner is required");
        Objects.requireNonNull(destination, "Schedule destination is required");
        if (!"slack".equals(destination.transport())) {
            throw new IllegalArgumentException("Unsupported scheduled delivery transport");
        }
        owner.requireOwnerAccountId();
        if (!java.util.Set.of("slack", "scheduler").contains(owner.origin())) {
            throw new SecurityException("Slack delivery requires a trusted Slack owner");
        }
        if (!destination.channelId().matches("[CGD][A-Z0-9]+")) {
            throw new IllegalArgumentException("A Slack channel ID is required");
        }
        if (destination.threadId() != null && !destination.threadId().matches("[0-9]+\\.[0-9]{6}")) {
            throw new IllegalArgumentException("Slack thread must be an explicit parent message timestamp");
        }
        return new DeliveryTransport.Destination(
                new TrustedScope(
                        owner.workspaceId(),
                        owner.requesterId(),
                        destination.channelId(),
                        owner.botId(),
                        "slack",
                        owner.ownerAccountId()),
                destination.threadId());
    }
}
