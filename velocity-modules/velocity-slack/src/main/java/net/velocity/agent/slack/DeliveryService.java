package net.velocity.agent.slack;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;

import net.velocity.agent.AgentGateway;
import net.velocity.agent.delivery.DeliveryJournal;
import net.velocity.agent.delivery.DeliveryTransport;
import net.velocity.agent.delivery.DeliveryTransport.*;
import net.velocity.agent.state.AcceptedCall;
import net.velocity.agent.state.ExecutionSnapshot;
import net.velocity.agent.state.ExecutionStatus;
import net.velocity.agent.state.TaskOutcome;
import net.velocity.usage.UsageReports;

/** Delivers saved progress; never consumes model token events or holds repository locks over the network. */
public final class DeliveryService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);
    private static final Gson JSON = new Gson();
    private final AgentGateway gateway;
    private final DeliveryJournal journal;
    private final DeliveryTransport transport;
    private final String botName;
    private final DeliveryUsageFooter usageFooter;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final Map<String, Monitor> monitored = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public DeliveryService(
            AgentGateway gateway,
            DeliveryJournal journal,
            DeliveryTransport transport,
            String botName) {
        this(gateway, journal, transport, botName, null);
    }

    public DeliveryService(
            AgentGateway gateway,
            DeliveryJournal journal,
            DeliveryTransport transport,
            String botName,
            UsageReports usageReports) {
        this(gateway, journal, transport, botName, usageReports, Clock.systemUTC());
    }

    public DeliveryService(
            AgentGateway gateway,
            DeliveryJournal journal,
            DeliveryTransport transport,
            String botName,
            UsageReports usageReports,
            Clock clock) {
        this.gateway = Objects.requireNonNull(gateway);
        this.journal = Objects.requireNonNull(journal);
        this.transport = Objects.requireNonNull(transport);
        this.botName = botName == null || botName.isBlank() ? "Velocity" : botName;
        this.usageFooter = new DeliveryUsageFooter(usageReports);
        this.clock = Objects.requireNonNull(clock);
        scheduler = Executors.newScheduledThreadPool(
                4,
                Thread.ofPlatform().daemon()
                        .name("agent-delivery-monitor").factory());
        gateway.registerDeletionBarrier(this::delete);
    }

    public void monitor(String id) {
        if (closed) {
            return;
        }
        monitored.compute(id, (key, current) -> {
            if (current != null) {
                current.generation.incrementAndGet();
                return current;
            }
            Monitor monitor = new Monitor();
            scheduler.execute(() -> tick(id, monitor));
            return monitor;
        });
    }

    public void recover() {
        for (ExecutionSnapshot snapshot : gateway.list()) {
            if (slack(snapshot)) {
                monitor(snapshot.executionId);
            }
        }
    }

    /** Local, acknowledged Slack message identities already represented by this execution's conversation. */
    public Set<String> representedMessageIds(String id) {
        Set<String> result = new HashSet<>();
        for (var turn : journal.read(id).turns.values()) {
            if (turn.streamId != null) {
                result.add(turn.streamId);
            }
            if (turn.cardId != null) {
                result.add(turn.cardId);
            }
            if (turn.terminalNoticeId != null) {
                result.add(turn.terminalNoticeId);
            }
        }
        return Set.copyOf(result);
    }

    private void tick(String id, Monitor monitor) {
        long generation = monitor.generation.get();
        boolean again = false;
        try {
            again = deliverOnce(id);
            if (!again) {
                // Administrative HTTP controls can resume a Slack task without an ingress callback.
                var latest = gateway.get(id);
                boolean live = latest.status == ExecutionStatus.running
                        || latest.status == ExecutionStatus.waiting_for_user;
                boolean uncertain = journal.read(id).turns.values().stream()
                        .anyMatch(turn -> turn.pending != null);
                again = live && !uncertain;
            }
        } catch (RuntimeException failure) {
            log.warn(
                    "executionId={} stage=delivery outcome=delivery_paused exception={}",
                    id,
                    failure.getClass().getSimpleName());
        }
        boolean repeat = again;
        monitored.computeIfPresent(id, (key, current) -> {
            if (current != monitor) {
                return current;
            }
            if (!closed && (repeat || generation != current.generation.get())) {
                scheduler.schedule(() -> tick(id, monitor), 2, TimeUnit.SECONDS);
                return current;
            }
            return null;
        });
    }

    /** Returns true only while another observation is needed. Exposed for deterministic reference scenarios. */
    public boolean deliverOnce(String id) {
        var observed = gateway.get(id);
        if (!slack(observed)) {
            return false;
        }
        // Deletion holds the gateway lifecycle lock while notifying this service. Never acquire
        // that lock while holding a delivery lock; a resumed turn is checked again below.
        boolean workerSettled = gateway.settled(id);
        synchronized (locks.computeIfAbsent(id, ignored -> new Object())) {
            ExecutionSnapshot snapshot = gateway.get(id);
            if (!slack(snapshot)) {
                return false;
            }
            if (snapshot.deliveryTurn != observed.deliveryTurn) {
                return true;
            }
            var state = journal.read(id);
            boolean cancelling = snapshot.cancellationRequested
                    || snapshot.status == ExecutionStatus.cancelled;
            // Cancellation must be able to close a known stream even when an interrupted tool ignores
            // interruption. It never publishes an unfinalized response or a success footer.
            if (cancelling && !workerSettled) {
                return closeCancelled(state, snapshot);
            }
            // Settlement was read outside this lock to preserve the deletion lock order. A terminal
            // snapshot must still be the exact observation whose worker settlement was inspected;
            // otherwise a worker could have launched and finished inference between those reads.
            if (snapshot.status != ExecutionStatus.running && snapshot.revision != observed.revision) {
                return true;
            }
            if (snapshot.status != ExecutionStatus.running && !workerSettled) {
                return true;
            }
            // Older uncertain turns must be reconciled before another answer may be started.
            for (var prior : state.turns.values()) {
                if (prior.pending != null && !reconcile(state, prior)) {
                    return false;
                }
                if (prior.number < snapshot.deliveryTurn && prior.streamId != null && !prior.stopped) {
                    if (!prior.streamClosed
                            && !perform(state, prior, "stop", "", null, null, prior.acknowledgedTextOffset)) {
                        return closedStreamReady(prior);
                    }
                    // A confirmed closed old turn needs no further write or replacement content.
                    prior.stopped = true;
                    prior.completed = true;
                    journal.save(state);
                }
            }
            Destination destination = new Destination(snapshot.scope, snapshot.sourceTurn.threadId());
            var turn = state.turns.computeIfAbsent(snapshot.deliveryTurn, number -> {
                var value = new DeliveryJournal.Turn();
                value.number = number;
                value.destination = destination;
                value.ownerAccountId = snapshot.scope.requireOwnerAccountId();
                return value;
            });
            if (!snapshot.scope.requireOwnerAccountId().equals(turn.ownerAccountId)
                    || !turn.destination
                            .equals(new Destination(snapshot.scope, snapshot.sourceTurn.threadId()))) {
                throw new IllegalStateException("Delivery destination changed");
            }
            if (turn.completed) {
                acknowledgeLearning(snapshot, turn);
                return false;
            }
            if (turn.streamId == null) {
                var initial = DeliveryProgress.initial(snapshot, botName);
                if (!perform(state, turn, "start", "", null, null, 0, initial.chunks(), initial.snapshot())) {
                    return false;
                }
            }
            if (!progress(state, turn, snapshot)) {
                return closedStreamReady(turn);
            }
            if (snapshot.status == ExecutionStatus.running) {
                return true;
            }
            String answer = response(snapshot);
            String digest = SlackControlBinding.digest(answer);
            if (turn.answerDigest != null && !digest.equals(turn.answerDigest)) {
                throw new IllegalStateException("Saved answer changed within a delivery turn");
            }
            turn.answerDigest = digest;
            var manifest = snapshot.status == ExecutionStatus.succeeded && !snapshot.cancellationRequested
                    ? List.copyOf(snapshot.outputReports)
                    : List.<net.velocity.agent.state.OutputReportReference>of();
            String payloadDigest = SlackControlBinding.digest(JSON.toJson(List.of(answer, manifest)));
            if (turn.payloadDigest != null && !turn.payloadDigest.equals(payloadDigest)) {
                throw new IllegalStateException("Saved delivery payload changed within a delivery turn");
            }
            turn.payloadDigest = payloadDigest;
            turn.goalGeneration = snapshot.goalGeneration;
            turn.reportManifest = manifest;
            if (!turn.footerFrozen) {
                turn.footerBlocks = snapshot.status == ExecutionStatus.succeeded
                        && !snapshot.cancellationRequested
                                ? usageFooter.render(snapshot)
                                : null;
                turn.footerFrozen = true;
            }
            journal.save(state);
            String footer = turn.footerBlocks;
            if (turn.streamClosed && !turn.stopped) {
                String blocks = replacementBlocks(answer, footer);
                if (blocks == null) {
                    turn.failure = "Closed-stream response exceeds Slack message block limits; the full saved response remains available.";
                    turn.lastErrorCode = "replacement_too_large";
                    turn.lastFailedOperation = "update";
                    journal.save(state);
                    return false;
                }
                if (!perform(state, turn, "update", answer, blocks, digest, answer.length())) {
                    return closedStreamReady(turn);
                }
            }
            while (!turn.streamClosed && !turn.stopped && turn.acknowledgedTextOffset < answer.length()) {
                int offset = turn.acknowledgedTextOffset;
                int end = answer.offsetByCodePoints(
                        offset,
                        Math.min(12_000, answer.codePointCount(offset, answer.length())));
                String chunk = answer.substring(turn.acknowledgedTextOffset, end);
                String chunks = turn.structuredStream
                        ? JSON.toJson(List.of(Map.of("type", "markdown_text", "text", chunk)))
                        : null;
                if (!perform(state, turn, "answer", chunk, null, digest, end, chunks, null)) {
                    return closedStreamReady(turn);
                }
            }
            // Completion activity must not get ahead of the acknowledged answer.
            if (!progress(state, turn, snapshot, true)) {
                return closedStreamReady(turn);
            }
            if (turn.streamClosed && !turn.stopped) {
                return true;
            }
            if (!turn.stopped && !perform(
                    state,
                    turn,
                    "stop",
                    "",
                    footer,
                    digest,
                    turn.acknowledgedTextOffset)) {
                return closedStreamReady(turn);
            }
            if (snapshot.status == ExecutionStatus.waiting_for_user && turn.cardId == null) {
                if (!perform(
                        state,
                        turn,
                        "post",
                        awaitingApproval(snapshot)
                                ? "Approval required. Use the buttons below, or reply approve or decline in this thread."
                                : "Input required. Reply in this thread to answer.",
                        controls(snapshot),
                        digest,
                        turn.acknowledgedTextOffset)) {
                    return false;
                }
            }
            for (var report : turn.reportManifest) {
                var upload = turn.uploads
                        .computeIfAbsent(report.reportId(), ignored -> new DeliveryJournal.Upload());
                if (upload.fileId == null && !performFile(state, turn, "file_prepare", report, upload)) {
                    return false;
                }
                if (!upload.bytesAcknowledged && !performFile(state, turn, "file_upload", report, upload)) {
                    return false;
                }
                if (!upload.completed && !performFile(state, turn, "file_complete", report, upload)) {
                    return false;
                }
            }
            if (snapshot.status.terminal() && turn.terminalNoticeId == null) {
                if (turn.terminalNotice == null) {
                    String text = terminalNoticeText(snapshot);
                    String binding = SlackControlBinding.digest(
                            JSON.toJson(
                                    List.of(
                                            snapshot.executionId,
                                            turn.number,
                                            turn.destination,
                                            snapshot.goalGeneration,
                                            snapshot.status,
                                            payloadDigest,
                                            text)));
                    turn.terminalNotice = new DeliveryJournal.TerminalNotice(
                            snapshot.goalGeneration,
                            snapshot.status,
                            reportedOutcome(snapshot),
                            payloadDigest,
                            text,
                            binding);
                    journal.save(state);
                }
                if (!turn.rejectedTerminalNotices.isEmpty()) {
                    if (turn.terminalNoticeRetryAt == 0) {
                        return false;
                    }
                    if (clock.millis() < turn.terminalNoticeRetryAt) {
                        return true;
                    }
                }
                if (!perform(
                        state,
                        turn,
                        "terminal_notice",
                        turn.terminalNotice.text(),
                        null,
                        turn.terminalNotice.bindingDigest(),
                        turn.acknowledgedTextOffset)) {
                    return turn.pending == null
                            && (turn.terminalNoticeRetryAt > 0 || turn.rejectedTerminalNotices.isEmpty());
                }
            }
            turn.completed = true;
            journal.save(state);
            acknowledgeLearning(snapshot, turn);
            return false;
        }
    }

    private static TaskOutcome.Status reportedOutcome(ExecutionSnapshot snapshot) {
        return snapshot.taskOutcome != null
                && snapshot.taskOutcome.goalGeneration() == snapshot.goalGeneration
                        ? snapshot.taskOutcome.status()
                        : null;
    }

    private static String terminalNoticeText(ExecutionSnapshot snapshot) {
        String status = switch (snapshot.status) {
            case succeeded -> switch (reportedOutcome(snapshot)) {
                case partial -> "Response complete. The agent reports that the task is incomplete.";
                case blocked -> "Response complete. The agent reports that the task is blocked.";
                case failed -> "Response complete. The agent reports that the task failed.";
                case completed -> "Response complete.";
                case null -> "Response complete.";
            };
            case partial -> switch (Objects.toString(snapshot.failure, "")) {
                case "active_time_limit" ->
                    "Task stopped: the execution time limit was reached. Work remains incomplete.";
                case "task_token_ceiling" ->
                    "Task stopped: the task token ceiling was reached. Work remains incomplete.";
                default -> "Task stopped with incomplete work.";
            };
            case blocked -> "Task blocked; further action is required.";
            case failed -> "Task failed.";
            case cancelled -> "Task cancelled. Completed external effects were not rolled back.";
            default -> throw new IllegalArgumentException("Terminal notice requires a settled terminal task");
        };
        return "<@" + snapshot.scope.requesterId() + "> " + status
                + (snapshot.status == ExecutionStatus.cancelled ? "" : " See the saved response above.");
    }

    private void acknowledgeLearning(ExecutionSnapshot snapshot, DeliveryJournal.Turn turn) {
        if (snapshot.status == ExecutionStatus.succeeded && !snapshot.cancellationRequested && turn.completed
                && turn.stopped && turn.pending == null && turn.number == snapshot.deliveryTurn
                && turn.goalGeneration == snapshot.goalGeneration
                && turn.acknowledgedTextOffset == response(snapshot).length()
                && turn.reportManifest.equals(snapshot.outputReports)
                && turn.reportManifest.stream().allMatch(
                        report -> turn.uploads.containsKey(report.reportId())
                                && turn.uploads.get(report.reportId()).completed)) {
            gateway.recordAcknowledgedDelivery(snapshot);
        }
    }

    private boolean performFile(
            DeliveryJournal.State state,
            DeliveryJournal.Turn turn,
            String kind,
            net.velocity.agent.state.OutputReportReference report,
            DeliveryJournal.Upload upload) {
        var latest = gateway.get(state.executionId);
        if (latest.deliveryTurn != turn.number || latest.goalGeneration != turn.goalGeneration
                || latest.cancellationRequested || latest.status != ExecutionStatus.succeeded
                || latest.sourceTurn == null
                || !turn.destination.equals(new Destination(latest.scope, latest.sourceTurn.threadId()))
                || !latest.outputReports.equals(turn.reportManifest)) {
            return false;
        }
        if (turn.pending != null) {
            throw new IllegalStateException("Unsettled delivery operation");
        }
        var operation = new Operation(
                UUID.randomUUID().toString(),
                state.executionId,
                turn.number,
                turn.destination,
                kind,
                upload.fileId,
                "",
                null,
                turn.payloadDigest,
                0,
                null,
                null,
                new FilePayload(report, upload.uploadUrl));
        turn.pending = operation;
        journal.save(state);
        try {
            acknowledge(state, turn, transport.execute(operation));
            return true;
        } catch (Exception failure) {
            turn.pending = operation;
            turn.uncertain = true;
            recordFailure(turn, operation, failure);
            turn.failure = "Report delivery paused at " + kind
                    + "; saved download remains available. Remote outcome requires inspection; it is never blindly retried.";
            journal.save(state);
            log.warn(
                    "executionId={} stage=delivery operation={} outcome=uncertain errorCode={}",
                    state.executionId,
                    kind,
                    turn.lastErrorCode);
            return false;
        }
    }

    private boolean progress(
            DeliveryJournal.State state,
            DeliveryJournal.Turn turn,
            ExecutionSnapshot snapshot) {
        return progress(state, turn, snapshot, false);
    }

    private boolean progress(
            DeliveryJournal.State state,
            DeliveryJournal.Turn turn,
            ExecutionSnapshot snapshot,
            boolean answerDelivered) {
        if (turn.progressDisabled || !turn.structuredStream || turn.stopped) {
            return true;
        }
        for (var batch : DeliveryProgress
                .updates(snapshot, botName, turn.acknowledgedProgress, answerDelivered)) {
            if (!perform(state, turn, "progress", "", null, null, 0, batch.chunks(), batch.snapshot())) {
                return false;
            }
            if (turn.progressDisabled) {
                break;
            }
        }
        return true;
    }

    private boolean closeCancelled(DeliveryJournal.State state, ExecutionSnapshot snapshot) {
        var turn = state.turns.get(snapshot.deliveryTurn);
        if (turn == null) {
            // A waiting task can be cancelled after its delivery turn advances but before the old
            // activity is closed. Close any identified stream; do not create an unfinalized reply.
            for (var prior : state.turns.values()) {
                if (prior.pending != null && !reconcile(state, prior)) {
                    return false;
                }
                if (prior.streamId != null && !prior.stopped && !prior.streamClosed
                        && !perform(state, prior, "stop", "", null, null, prior.acknowledgedTextOffset)) {
                    return closedStreamReady(prior);
                }
            }
        }
        if (turn == null || turn.streamId == null) {
            return true;
        }
        if (turn.completed) {
            return false;
        }
        if (turn.stopped) {
            return true;
        }
        if (turn.pending != null && !reconcile(state, turn)) {
            return false;
        }
        if (!progress(state, turn, snapshot)) {
            return false;
        }
        if (turn.streamClosed) {
            return true;
        } // The cancelled answer may only replace content after worker settlement.
        if (!perform(state, turn, "stop", "", null, null, turn.acknowledgedTextOffset)) {
            return closedStreamReady(turn);
        }
        // The activity can stop now; the terminal notification must wait for actual worker settlement.
        return true;
    }

    /** Keep the entire response in one existing message; never silently truncate to fit an update. */
    private static String replacementBlocks(String answer, String footer) {
        var blocks = new com.google.gson.JsonArray();
        if (answer.codePointCount(0, answer.length()) <= 12_000) {
            blocks.add(JSON.toJsonTree(Map.of("type", "markdown", "text", answer)));
        } else {
            int limit = footer == null ? 50 : 49;
            for (int offset = 0; offset < answer.length();) {
                if (blocks.size() == limit) {
                    return null;
                }
                int end = answer.offsetByCodePoints(
                        offset,
                        Math.min(3_000, answer.codePointCount(offset, answer.length())));
                blocks.add(
                        JSON.toJsonTree(
                                Map.of(
                                        "type",
                                        "section",
                                        "text",
                                        Map.of(
                                                "type",
                                                "plain_text",
                                                "text",
                                                answer.substring(offset, end),
                                                "emoji",
                                                false))));
                offset = end;
            }
        }
        if (footer != null) {
            blocks.addAll(com.google.gson.JsonParser.parseString(footer).getAsJsonArray());
        }
        return JSON.toJson(blocks);
    }

    private static boolean closedStreamReady(DeliveryJournal.Turn turn) {
        return turn.streamClosed && turn.pending == null;
    }

    private boolean perform(
            DeliveryJournal.State state,
            DeliveryJournal.Turn turn,
            String kind,
            String text,
            String blocks,
            String digest,
            int offset) {
        return perform(state, turn, kind, text, blocks, digest, offset, null, null);
    }

    private boolean perform(
            DeliveryJournal.State state,
            DeliveryJournal.Turn turn,
            String kind,
            String text,
            String blocks,
            String digest,
            int offset,
            String chunks,
            String progressSnapshot) {
        if (turn.pending != null) {
            throw new IllegalStateException("Unsettled delivery operation");
        }
        // get() is a repository read, not a gateway lifecycle lock. Do not send an obsolete turn after a resume.
        var latest = gateway.get(state.executionId);
        if (latest.deliveryTurn != turn.number
                && !("stop".equals(kind) && blocks == null && latest.deliveryTurn > turn.number)) {
            return false;
        }
        if ("answer".equals(kind) && (latest.status == ExecutionStatus.running || latest.cancellationRequested
                && latest.status != ExecutionStatus.cancelled)) {
            return false;
        }
        if ("stop".equals(kind) && blocks != null && (latest.status != ExecutionStatus.succeeded
                || latest.cancellationRequested)) {
            return false;
        }
        if ("update".equals(kind) && (!turn.streamClosed || turn.streamId == null
                || latest.goalGeneration != turn.goalGeneration || latest.sourceTurn == null
                || !turn.destination.equals(new Destination(latest.scope, latest.sourceTurn.threadId()))
                || latest.status == ExecutionStatus.running
                || latest.cancellationRequested && latest.status != ExecutionStatus.cancelled
                || !Objects.equals(digest, SlackControlBinding.digest(response(latest)))
                || !Objects.equals(
                        blocks,
                        replacementBlocks(
                                response(latest),
                                latest.status == ExecutionStatus.succeeded && !latest.cancellationRequested
                                        ? turn.footerBlocks
                                        : null))
                || !turn.reportManifest.equals(
                        latest.status == ExecutionStatus.succeeded && !latest.cancellationRequested
                                ? latest.outputReports
                                : List.of()))) {
            return false;
        }
        if ("terminal_notice".equals(kind)) {
            var notice = turn.terminalNotice;
            var manifest = latest.status == ExecutionStatus.succeeded && !latest.cancellationRequested
                    ? latest.outputReports
                    : List.of();
            String currentPayload = SlackControlBinding
                    .digest(JSON.toJson(List.of(response(latest), manifest)));
            if (notice == null || !latest.status.terminal() || notice.status() != latest.status
                    || latest.cancellationRequested && latest.status != ExecutionStatus.cancelled
                    || notice.goalGeneration() != latest.goalGeneration
                    || notice.reportedOutcome() != reportedOutcome(latest)
                    || !notice.payloadDigest().equals(currentPayload)
                    || !notice.text().equals(text) || !notice.bindingDigest().equals(digest)
                    || latest.sourceTurn == null
                    || !turn.destination
                            .equals(new Destination(latest.scope, latest.sourceTurn.threadId()))) {
                return false;
            }
        }
        var operation = new Operation(
                UUID.randomUUID().toString(),
                state.executionId,
                turn.number,
                turn.destination,
                kind,
                "terminal_notice".equals(kind) ? null : turn.streamId,
                text,
                blocks,
                digest,
                offset,
                chunks,
                progressSnapshot);
        turn.pending = operation;
        if ("terminal_notice".equals(kind)) {
            turn.terminalNoticeRetryAt = 0;
        }
        journal.save(state); // Write-ahead: no external operation occurs before this succeeds.
        try {
            Receipt receipt = transport.execute(operation);
            acknowledge(state, turn, receipt);
            return true;
        } catch (Exception failure) {
            turn.pending = operation; // A failed receipt save must retain the original write-ahead intent.
            recordFailure(turn, operation, failure);
            if ("terminal_notice".equals(kind) && failure instanceof DeliveryTransport.Failure rejection
                    && rejection.acknowledgedAbsent()) {
                turn.rejectedTerminalNotices
                        .add(new DeliveryJournal.Rejection(operation, rejection.errorCode()));
                turn.pending = null;
                turn.uncertain = false;
                boolean retry = turn.rejectedTerminalNotices.size() < 3
                        && Set.of("rate_limited", "ratelimited").contains(rejection.errorCode());
                long now = Math.max(0, clock.millis());
                long delay = Math.max(60_000, rejection.retryAfterMillis());
                turn.terminalNoticeRetryAt = retry
                        ? delay > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + delay
                        : 0;
                turn.failure = "Slack terminal notice rejected (" + rejection.errorCode() + "). "
                        + (retry
                                ? "A bounded retry is scheduled."
                                : "Delivery requires operator inspection.");
                journal.save(state);
                log.warn(
                        "executionId={} stage=delivery operation=terminal_notice outcome=rejected errorCode={} retry={}",
                        state.executionId,
                        rejection.errorCode(),
                        retry);
                return false;
            }
            if (failure instanceof DeliveryTransport.Failure rejection && rejection.acknowledgedAbsent()
                    && "message_not_in_streaming_state".equals(rejection.errorCode())
                    && List.of("progress", "answer", "stop").contains(kind)
                    && turn.streamId != null && turn.streamId.equals(operation.remoteId())) {
                turn.rejectedOperations.add(new DeliveryJournal.Rejection(operation, rejection.errorCode()));
                turn.streamClosed = true;
                turn.progressDisabled = true;
                turn.progressBlocked = false;
                turn.pending = null;
                turn.uncertain = false;
                journal.save(state);
                log.warn(
                        "executionId={} stage=delivery operation={} outcome=stream_closed errorCode={}",
                        state.executionId,
                        kind,
                        turn.lastErrorCode);
                // Break a rejected answer/stop into a fresh settled observation; progress can continue.
                return "progress".equals(kind);
            }
            if ("progress".equals(kind) && (!(failure instanceof DeliveryTransport.Failure rejection)
                    || rejection.streamUsable())) {
                // Only optional activity may be abandoned. Preserve ambiguous intent for operator inspection,
                // but do not replay it or block the first final-answer append to this known stream.
                if (!(failure instanceof DeliveryTransport.Failure rejection)
                        || !rejection.acknowledgedAbsent()) {
                    abandonProgress(state, turn, operation);
                } else {
                    turn.pending = null;
                    turn.progressDisabled = true;
                    journal.save(state);
                }
                log.warn(
                        "executionId={} stage=delivery operation=progress outcome=progress_disabled errorCode={}",
                        state.executionId,
                        turn.lastErrorCode);
                return true;
            }
            if ("progress".equals(kind) && failure instanceof DeliveryTransport.Failure rejection
                    && !rejection.streamUsable()) {
                turn.progressBlocked = true;
            }
            turn.uncertain = true;
            journal.save(state);
            log.warn(
                    "executionId={} stage=delivery operation={} outcome=uncertain errorCode={}",
                    state.executionId,
                    kind,
                    turn.lastErrorCode);
            return false;
        }
    }

    private static void recordFailure(DeliveryJournal.Turn turn, Operation operation, Exception failure) {
        turn.lastErrorCode = failure instanceof DeliveryTransport.Failure rejection
                ? rejection.errorCode()
                : failure instanceof SecurityException ? "access_verification_failed" : "delivery_error";
        turn.lastFailedOperation = operation.kind();
        turn.failure = "Slack delivery paused at " + operation.kind() + " (" + turn.lastErrorCode + ").";
    }

    private boolean reconcile(DeliveryJournal.State state, DeliveryJournal.Turn turn) {
        Operation pending = turn.pending;
        try {
            var saved = turn.receipts.stream().filter(receipt -> pending.id().equals(receipt.operationId()))
                    .findFirst();
            if (saved.isPresent()) {
                acknowledge(state, turn, saved.get());
                return true;
            }
            var receipt = transport.inspect(turn.pending);
            if (receipt.isPresent()) {
                acknowledge(state, turn, receipt.get());
                return true;
            }
        } catch (Exception ignored) {
            /* A failed inspection must not become permission to resend. */ }
        if ("progress".equals(pending.kind()) && turn.streamId != null && !turn.progressBlocked) {
            abandonProgress(state, turn, pending);
            return true;
        }
        turn.pending = pending;
        turn.uncertain = true;
        journal.save(state);
        return false;
    }

    private void abandonProgress(
            DeliveryJournal.State state,
            DeliveryJournal.Turn turn,
            Operation operation) {
        if (turn.abandonedProgress.stream().noneMatch(saved -> saved.id().equals(operation.id()))) {
            turn.abandonedProgress.add(operation);
        }
        turn.pending = null;
        turn.progressDisabled = true;
        turn.uncertain = false; // Uncertainty is isolated in abandonedProgress, never silently acknowledged.
        journal.save(state);
    }

    private void acknowledge(DeliveryJournal.State state, DeliveryJournal.Turn turn, Receipt receipt) {
        Operation operation = turn.pending;
        if (receipt == null || !operation.id().equals(receipt.operationId())
                || receipt.remoteId() == null || receipt.remoteId().isBlank()) {
            throw new IllegalStateException("Missing delivery acknowledgement");
        }
        if (("file_upload".equals(operation.kind()) || "file_complete".equals(operation.kind())
                || "update".equals(operation.kind()))
                && !Objects.equals(operation.remoteId(), receipt.remoteId())) {
            throw new IllegalStateException("Delivery acknowledgement does not match its destination");
        }
        switch (operation.kind()) {
            case "start" -> {
                turn.streamId = receipt.remoteId();
                turn.lastProgress = operation.text();
                turn.structuredStream = operation.chunks() != null;
                turn.acknowledgedProgress = operation.progressSnapshot();
            }
            case "progress" -> {
                turn.lastProgress = operation.text().stripLeading();
                turn.acknowledgedProgress = operation.progressSnapshot();
            }
            case "answer" -> turn.acknowledgedTextOffset = operation.offset();
            case "update" -> {
                turn.acknowledgedTextOffset = operation.offset();
                turn.stopped = true;
            }
            case "stop" -> turn.stopped = true;
            case "post" -> turn.cardId = receipt.remoteId();
            case "terminal_notice" -> turn.terminalNoticeId = receipt.remoteId();
            case "file_prepare" -> {
                if (receipt.uploadUrl() == null || receipt.uploadUrl().isBlank()) {
                    throw new IllegalStateException("Missing upload destination acknowledgement");
                }
                var upload = turn.uploads.computeIfAbsent(
                        operation.file().report().reportId(),
                        ignored -> new DeliveryJournal.Upload());
                upload.fileId = receipt.remoteId();
                upload.uploadUrl = receipt.uploadUrl();
            }
            case "file_upload" ->
                turn.uploads.get(operation.file().report().reportId()).bytesAcknowledged = true;
            case "file_complete" -> {
                var upload = turn.uploads.get(operation.file().report().reportId());
                upload.completed = true;
                upload.uploadUrl = null;
            }
            default -> {
            }
        }
        if (turn.receipts.stream().noneMatch(saved -> saved.operationId().equals(receipt.operationId()))) {
            turn.receipts.add(receipt);
        }
        turn.pending = null;
        turn.uncertain = false;
        turn.failure = null;
        journal.save(state);
    }

    private static boolean slack(ExecutionSnapshot snapshot) {
        return snapshot.sourceTurn != null && snapshot.scope != null
                && "slack".equals(snapshot.scope.origin());
    }

    private static String response(ExecutionSnapshot snapshot) {
        if (snapshot.status == ExecutionStatus.waiting_for_user) {
            if (awaitingApproval(snapshot)) {
                var held = snapshot.calls.stream().filter(AcceptedCall::awaitingApproval).toList();
                StringBuilder text = new StringBuilder(
                        held.size() == 1
                                ? "Approval required. Velocity wants to run this action:"
                                : "Approval required. Velocity wants to run these actions:");
                for (var call : held) {
                    text.append("\n\n").append(approvalPreview(call));
                }
                text.append("\n\nNothing runs until you decide. Approve or decline below, reply approve or ")
                        .append("decline in this thread, or decide in the Velocity console. ")
                        .append("Reply with a correction instead to change what Velocity does.");
                return text.toString();
            }
            StringBuilder text = new StringBuilder("Input required.");
            snapshot.pendingQuestions.forEach(question -> text.append("\n").append(question.text()));
            return text.toString();
        }
        if (snapshot.finalAnswer != null && !snapshot.finalAnswer.isBlank()) {
            return snapshot.finalAnswer;
        }
        return switch (snapshot.status) {
            case cancelled -> "Task cancelled. External effects already completed were not rolled back.";
            case succeeded -> "Response complete.";
            case failed -> "The task failed. Inspect its saved execution for details.";
            default -> "Task " + snapshot.status + ". Inspect its saved execution for details.";
        };
    }

    private static boolean awaitingApproval(ExecutionSnapshot snapshot) {
        return snapshot.pendingQuestions.isEmpty()
                && snapshot.calls.stream().anyMatch(AcceptedCall::awaitingApproval);
    }

    /**
     * What a held call will do: the trusted summary its tool rendered from the actual arguments when one
     * exists, otherwise a short, bounded view of the raw arguments. Never tool output or model reasoning.
     */
    private static String approvalPreview(AcceptedCall call) {
        String summary = call.approval == null ? null : call.approval.summary();
        if (summary != null && !summary.isBlank()) {
            return summary.strip();
        }
        return call.name + ' ' + approvalInputPreview(call);
    }

    private static String approvalInputPreview(AcceptedCall call) {
        String json;
        try {
            json = JSON.toJson(call.input);
        } catch (RuntimeException unencodable) {
            return "";
        }
        if (json.length() > 300) {
            json = json.substring(0, 297) + "...";
        }
        return json;
    }

    private static String controls(ExecutionSnapshot snapshot) {
        var elements = new java.util.ArrayList<Map<String, Object>>();
        boolean approval = awaitingApproval(snapshot);
        if (approval) {
            var approve = new java.util.LinkedHashMap<>(button("Approve", "agent_approve", snapshot));
            approve.put("style", "primary");
            elements.add(approve);
            elements.add(button("Decline", "agent_decline", snapshot));
        }
        elements.add(button("Cancel task", "agent_cancel", snapshot));
        return JSON.toJson(
                List.of(
                        Map.of(
                                "type",
                                "section",
                                "text",
                                Map.of(
                                        "type",
                                        "mrkdwn",
                                        "text",
                                        approval
                                                ? "Approve runs the action exactly as shown above; Decline skips it for good. "
                                                        + "Reply in this thread to correct the request instead."
                                                : "Reply in this thread to answer or correct the request.")),
                        Map.of("type", "actions", "elements", elements)));
    }

    private static Map<String, Object> button(String text, String action, ExecutionSnapshot snapshot) {
        return Map.of(
                "type",
                "button",
                "text",
                Map.of("type", "plain_text", "text", text),
                "action_id",
                action,
                "value",
                SlackControlBinding.value(snapshot, action));
    }

    public void delete(String id) {
        synchronized (locks.computeIfAbsent(id, ignored -> new Object())) {
            monitored.remove(id);
            journal.delete(id);
        }
    }

    @Override
    public void close() {
        closed = true;
        scheduler.shutdownNow();
    }

    private static final class Monitor {

        final java.util.concurrent.atomic.AtomicLong generation = new java.util.concurrent.atomic.AtomicLong();
    }
}
