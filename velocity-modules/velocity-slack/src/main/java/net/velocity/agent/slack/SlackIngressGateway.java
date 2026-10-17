package net.velocity.agent.slack;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.velocity.agent.AgentGateway;
import net.velocity.agent.ContextHooks;
import net.velocity.agent.persistence.ConflictException;
import net.velocity.agent.state.*;
import net.velocity.inference.objects.InferenceMessage;

/**
 * Trusted Socket Mode event adapter. All lifecycle and inference behavior
 * belongs to AgentGateway.
 */
public final class SlackIngressGateway {

    /** {@code files} are the message's attachments as untrusted metadata; they are downloaded only when applied. */
    public record Message(
            String workspaceId,
            String channelId,
            String requesterId,
            String botId,
            String threadId,
            String timestamp,
            String text,
            boolean botMessage,
            List<SlackFile> files) {

        public Message {
            files = files == null ? List.of() : List.copyOf(files);
        }
    }

    public record Control(
            String workspaceId,
            String channelId,
            String requesterId,
            String botId,
            String threadId,
            String action,
            String value) {}

    private final AgentGateway gateway;
    private final ContextHooks hooks;
    private final DeliveryService delivery;
    private final SlackAccessVerifier access;
    private final SlackThreadHistory threadHistory;
    private final SlackFileDownloader files;
    private final net.velocity.agent.accounts.AccountRepository accounts;
    private final java.time.Clock clock;
    private final Map<String, Boolean> seen = new LinkedHashMap<>();
    private final Object[] threads = new Object[256];

    public SlackIngressGateway(
            AgentGateway gateway,
            ContextHooks hooks,
            DeliveryService delivery,
            SlackAccessVerifier access,
            SlackThreadHistory threadHistory,
            SlackFileDownloader files,
            net.velocity.agent.accounts.AccountRepository accounts,
            java.time.Clock clock) {
        this.gateway = Objects.requireNonNull(gateway);
        this.hooks = Objects.requireNonNull(hooks);
        this.delivery = Objects.requireNonNull(delivery);
        this.access = Objects.requireNonNull(access);
        this.threadHistory = Objects.requireNonNull(threadHistory);
        this.files = Objects.requireNonNull(files);
        this.accounts = Objects.requireNonNull(accounts);
        this.clock = Objects.requireNonNull(clock);
        java.util.Arrays.setAll(threads, ignored -> new Object());
    }

    public Optional<ExecutionSnapshot> message(Message message) {
        return message(message, true);
    }

    /**
     * Channel message subscriptions may continue an existing task without admitting
     * unrelated channel chat. A message without text is eligible only when it carries files.
     */
    public Optional<ExecutionSnapshot> message(Message message, boolean allowCreate) {
        if (message == null || message.botMessage() || !text(message.text()) && message.files().isEmpty()
                || !text(message.workspaceId()) || !text(message.channelId()) || !text(message.requesterId())
                || !text(message.botId()) || !text(message.timestamp())
                || message.requesterId().equals(message.botId())) {
            return Optional.empty();
        }
        String thread = text(message.threadId()) ? message.threadId() : message.timestamp();
        synchronized (threadLock(message.workspaceId(), message.channelId(), thread)) {
            String key = message.workspaceId() + "\n" + message.channelId() + "\n" + message.timestamp();
            synchronized (seen) {
                if (seen.containsKey(key)) {
                    return Optional.empty();
                }
            }
            TrustedScope scope = new TrustedScope(
                    message.workspaceId(),
                    message.requesterId(),
                    message.channelId(),
                    message.botId(),
                    "slack");
            scope = verifiedAccount(scope);
            List<ExecutionSnapshot> saved = gateway.list();
            var source = new SourceTurn(
                    message.workspaceId(),
                    message.channelId(),
                    thread,
                    message.timestamp());
            Optional<ExecutionSnapshot> duplicate = saved.stream()
                    .filter(task -> sameThread(task, message.workspaceId(), message.channelId(), thread))
                    .filter(task -> gateway.hasSourceTurn(task, source))
                    .findFirst();
            if (duplicate.isPresent()) {
                remember(key);
                delivery.monitor(duplicate.get().executionId);
                return duplicate;
            }
            Optional<ExecutionSnapshot> active = saved.stream()
                    .filter(task -> sameThread(task, message.workspaceId(), message.channelId(), thread))
                    .filter(
                            task -> task.status == ExecutionStatus.running
                                    || task.status == ExecutionStatus.waiting_for_user)
                    .max(Comparator.comparing(task -> task.createdAt));
            if (active.isEmpty() && !allowCreate) {
                return Optional.empty();
            }
            // A message.channels event may arrive before app_mention for the same Slack
            // message.
            // Only eligible admissions/controls enter the window, so an ignored channel
            // event cannot suppress a mention.
            ExecutionSnapshot result;
            String input = message.text() == null ? "" : message.text().strip();
            // A reply that carries files is always a correction or answer, never a control word.
            boolean control = active.isPresent() && message.files().isEmpty();
            if (control && (input.equalsIgnoreCase("cancel") || input.equalsIgnoreCase("stop"))) {
                ExecutionSnapshot task = active.get();
                requireInitiator(task, scope);
                result = gateway.cancel(task.executionId, scope, false, source);
            } else if (control && approvalDecision(input) != null
                    && active.get().status == ExecutionStatus.waiting_for_user
                    && active.get().pendingQuestions.isEmpty()
                    && active.get().calls.stream().anyMatch(AcceptedCall::awaitingApproval)) {
                ExecutionSnapshot task = active.get();
                requireInitiator(task, scope);
                // A thread reply cannot name one call, so it decides every held call at once.
                result = gateway.approve(
                        task.executionId,
                        null,
                        approvalDecision(input),
                        null,
                        scope,
                        source);
            } else {
                if (active.isPresent()) {
                    requireInitiator(active.get(), scope);
                }
                // Files are fetched only for an eligible message, after access was verified; a rejected or
                // failed download admits nothing, and the bytes are untrusted data.
                List<UploadedFile> uploads = files.download(message.files());
                if (input.isEmpty()) {
                    input = filesOnlyRequest(uploads);
                }
                if (active.isPresent()) {
                    ExecutionSnapshot task = active.get();
                    var context = threadHistory.fetch(scope, source, input);
                    // Retrieval may span several API calls while the worker makes progress.
                    task = gateway.get(task.executionId);
                    if (task.status.terminal()) {
                        if (!allowCreate) {
                            throw new ConflictException("Task completed while Slack history was loading");
                        }
                        result = create(message, scope, source, input, context, uploads);
                    } else {
                        context = linkDeliveredMessages(context, task);
                        if (task.status == ExecutionStatus.waiting_for_user) {
                            result = gateway.answer(
                                    task.executionId,
                                    List.of(input),
                                    task.revision,
                                    scope,
                                    source,
                                    context,
                                    uploads);
                        } else {
                            result = gateway.steer(
                                    task.executionId,
                                    input,
                                    scope,
                                    source,
                                    context,
                                    uploads);
                        }
                    }
                } else {
                    result = create(
                            message,
                            scope,
                            source,
                            input,
                            threadHistory.fetch(scope, source, input),
                            uploads);
                }
            }
            // Failed retrieval/admission must remain retryable; only durable success enters the window.
            remember(key);
            delivery.monitor(result.executionId);
            return Optional.of(result);
        }
    }

    /** {@code approve} / {@code yes} approve; {@code decline} / {@code deny} / {@code reject} decline. */
    private static Boolean approvalDecision(String input) {
        return switch (input.toLowerCase(java.util.Locale.ROOT).replaceAll("[.!]+$", "")) {
            case "approve", "approved", "yes", "ok", "go ahead" -> Boolean.TRUE;
            case "decline", "declined", "deny", "denied", "reject", "no" -> Boolean.FALSE;
            default -> null;
        };
    }

    /** The request text of a message that carried only files; the gateway lists the stored files separately. */
    static String filesOnlyRequest(List<UploadedFile> uploads) {
        var names = new StringBuilder();
        for (UploadedFile upload : uploads) {
            names.append(names.isEmpty() ? "" : ", ").append(upload.filename());
        }
        return "[Slack message with no text; attached files: " + names + "]";
    }

    private ExecutionSnapshot create(
            Message message,
            TrustedScope scope,
            SourceTurn source,
            String input,
            List<SourceMessage> context,
            List<UploadedFile> uploads) {
        List<InferenceMessage> history = hooks.history(scope, source);
        history = history == null ? List.of() : history;
        return gateway.create(
                new StartRequest(
                        input,
                        message.workspaceId() + ":" + message.channelId()
                                + ":" + source.threadId(),
                        scope,
                        hooks.instructions(scope),
                        history,
                        List.of(),
                        source,
                        null,
                        null,
                        context,
                        null,
                        uploads));
    }

    private List<SourceMessage> linkDeliveredMessages(
            List<SourceMessage> messages,
            ExecutionSnapshot task) {
        // Read local delivery receipts only. Remote recovery inspection is not conversation retrieval.
        Set<String> represented = delivery.representedMessageIds(task.executionId);
        // The acknowledged ID is authoritative even when Slack only returns bot_id, without user.
        return messages.stream().map(
                message -> represented.contains(message.source().messageId())
                        ? new SourceMessage(
                                message.source(),
                                message.authorId(),
                                message.botId(),
                                message.text(),
                                true)
                        : message)
                .toList();
    }

    /**
     * A bound button press from the requester: {@code agent_cancel} cancels the task, {@code agent_approve}
     * and {@code agent_decline} decide every held call at once, like a thread reply.
     */
    public ExecutionSnapshot control(Control control) {
        Objects.requireNonNull(control);
        boolean cancel = "agent_cancel".equals(control.action());
        Boolean decision = switch (Objects.toString(control.action(), "")) {
            case "agent_approve" -> Boolean.TRUE;
            case "agent_decline" -> Boolean.FALSE;
            default -> null;
        };
        if (!text(control.threadId()) || !text(control.value()) || !cancel && decision == null) {
            throw new IllegalArgumentException("Invalid Slack control");
        }
        synchronized (threadLock(control.workspaceId(), control.channelId(), control.threadId())) {
            String[] value = control.value().split(":", -1);
            if (value.length != 3) {
                throw new IllegalArgumentException("Invalid Slack control binding");
            }
            var task = gateway.get(value[0]);
            TrustedScope scope = new TrustedScope(
                    control.workspaceId(),
                    control.requesterId(),
                    control.channelId(),
                    control.botId(),
                    "slack");
            scope = verifiedAccount(scope);
            requireInitiator(task, scope);
            if (!sameThread(task, control.workspaceId(), control.channelId(), control.threadId())
                    || task.status != ExecutionStatus.waiting_for_user
                    || !SlackControlBinding.value(task, control.action()).equals(control.value())) {
                throw new IllegalArgumentException("Stale or mismatched Slack control");
            }
            ExecutionSnapshot result;
            if (cancel) {
                result = gateway.cancel(task.executionId, scope, false);
            } else {
                if (!task.pendingQuestions.isEmpty()
                        || task.calls.stream().noneMatch(AcceptedCall::awaitingApproval)) {
                    throw new IllegalArgumentException("Stale or mismatched Slack control");
                }
                result = gateway.approve(task.executionId, null, decision, task.revision, scope, null);
            }
            delivery.monitor(result.executionId);
            return result;
        }
    }

    private TrustedScope verifiedAccount(TrustedScope transport) {
        if (transport.ownerAccountId() != null) {
            throw new SecurityException("Ingress cannot accept supplied account authority");
        }
        access.verify(transport);
        var account = accounts
                .resolveOrCreateSlack(transport.workspaceId(), transport.requesterId(), clock.instant());
        if (!account.slackWorkspaceId().equals(transport.workspaceId())
                || !account.slackUserId().equals(transport.requesterId())) {
            throw new SecurityException("Registered account does not match verified Slack identity");
        }
        return new TrustedScope(
                transport.workspaceId(),
                transport.requesterId(),
                transport.channelId(),
                transport.botId(),
                transport.origin(),
                account.id());
    }

    private static void requireInitiator(ExecutionSnapshot task, TrustedScope actor) {
        if (!actor.equals(task.scope)) {
            throw new SecurityException("Only the initiating Slack requester may control this task");
        }
    }

    private static boolean sameThread(
            ExecutionSnapshot task,
            String workspace,
            String channel,
            String thread) {
        return task.sourceTurn != null && "slack".equals(task.scope.origin())
                && workspace.equals(task.sourceTurn.workspaceId())
                && channel.equals(task.sourceTurn.channelId())
                && thread.equals(task.sourceTurn.threadId());
    }

    private Object threadLock(String workspace, String channel, String thread) {
        return threads[Math.floorMod(Objects.hash(workspace, channel, thread), threads.length)];
    }

    private void remember(String key) {
        synchronized (seen) {
            seen.put(key, true);
            if (seen.size() > 2000) {
                seen.remove(seen.keySet().iterator().next());
            }
        }
    }

    private static boolean text(String value) {
        return value != null && !value.isBlank();
    }
}
