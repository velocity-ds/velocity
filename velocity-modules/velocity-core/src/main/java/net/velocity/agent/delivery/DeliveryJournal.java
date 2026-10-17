package net.velocity.agent.delivery;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.velocity.agent.delivery.DeliveryTransport.Destination;
import net.velocity.agent.delivery.DeliveryTransport.Operation;
import net.velocity.agent.delivery.DeliveryTransport.Receipt;
import net.velocity.agent.state.ExecutionStatus;
import net.velocity.agent.state.OutputReportReference;
import net.velocity.agent.state.TaskOutcome;

/** Versioned write-ahead delivery state and its storage contract. */
public abstract class DeliveryJournal {

    protected static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();

    public static final class State {

        public int version = 1;
        public String executionId;
        public Map<Long, Turn> turns = new LinkedHashMap<>();
        /** Storage concurrency token; deliberately excluded from the serialized delivery state. */
        public transient long storageRevision;
    }

    public static final class Turn {

        public long number;
        public Destination destination;
        public String ownerAccountId;
        public String streamId;
        public String cardId;
        /** A separate, acknowledged terminal reply; never an input-control card or the answer stream. */
        public String terminalNoticeId;
        public TerminalNotice terminalNotice;
        /** Positive non-delivery evidence; only bounded rate-limit retries may create another intent. */
        public List<Rejection> rejectedTerminalNotices = new ArrayList<>();
        public long terminalNoticeRetryAt;
        public String answerDigest;
        /** Frozen together with the final answer. */
        public String payloadDigest;
        /** Exact final footer saved with the answer. */
        public boolean footerFrozen;
        public String footerBlocks;
        public long goalGeneration;
        public List<OutputReportReference> reportManifest = new ArrayList<>();
        public Map<String, Upload> uploads = new LinkedHashMap<>();
        public int acknowledgedTextOffset;
        public String lastProgress;
        public boolean structuredStream;
        public String acknowledgedProgress;
        public boolean progressDisabled;
        public boolean progressBlocked;
        /** Slack explicitly rejected an append/stop because this known message is no longer streaming. */
        public boolean streamClosed;
        /** Rejections are evidence of non-delivery, never successful receipts. */
        public List<Rejection> rejectedOperations = new ArrayList<>();
        /** Optional writes whose outcome is unknown. Never replayed, and never treated as acknowledged. */
        public List<Operation> abandonedProgress = new ArrayList<>();
        public boolean stopped;
        public boolean completed;
        public boolean uncertain;
        /** Durable terminal failure or explanation of an uncertain scheduled post. */
        public String failure;
        public String lastErrorCode;
        public String lastFailedOperation;
        public Operation pending;
        public List<Receipt> receipts = new ArrayList<>();
    }

    public record Rejection(Operation operation, String errorCode) {}

    public record TerminalNotice(
            long goalGeneration,
            ExecutionStatus status,
            TaskOutcome.Status reportedOutcome,
            String payloadDigest,
            String text,
            String bindingDigest) {}

    public static final class Upload {

        public String fileId;
        public String uploadUrl;
        public boolean bytesAcknowledged;
        public boolean completed;
    }

    public abstract State read(String id);

    public abstract void save(State state);

    public abstract void delete(String id);

    protected static void validateId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,100}")) {
            throw new IllegalArgumentException("Invalid execution ID");
        }
    }

    protected static State decode(String json, String id) {
        State state = JSON.fromJson(json, State.class);
        validate(state, id);
        return state;
    }

    protected static void validate(State state, String id) {
        validateId(id);
        if (state == null || state.version != 1 || !id.equals(state.executionId) || state.turns == null) {
            throw new IllegalStateException("Unsupported or corrupt delivery state");
        }
        for (var entry : state.turns.entrySet()) {
            Turn turn = entry.getValue();
            if (turn == null || turn.number != entry.getKey() || turn.destination == null
                    || turn.ownerAccountId == null
                    || !turn.ownerAccountId.equals(turn.destination.scope().ownerAccountId())
                    || turn.receipts == null || turn.abandonedProgress == null
                    || turn.rejectedOperations == null
                    || turn.acknowledgedTextOffset < 0
                    || turn.footerBlocks != null && (!turn.footerFrozen || turn.footerBlocks.length() > 6000)
                    || turn.reportManifest == null || turn.uploads == null
                    || (turn.pending != null && (!id.equals(turn.pending.executionId())
                            || turn.number != turn.pending.turn()
                            || !turn.destination.equals(turn.pending.destination())))) {
                throw new IllegalStateException("Corrupt delivery turn");
            }
            for (var rejection : turn.rejectedOperations) {
                var operation = rejection == null ? null : rejection.operation();
                if (operation == null || !id.equals(operation.executionId())
                        || operation.turn() != turn.number
                        || !turn.destination.equals(operation.destination())
                        || !Objects.equals(turn.streamId, operation.remoteId())
                        || !List.of("progress", "answer", "stop").contains(operation.kind())
                        || !"message_not_in_streaming_state".equals(rejection.errorCode())) {
                    throw new IllegalStateException("Corrupt closed-stream rejection");
                }
            }
            if (turn.streamClosed && (turn.streamId == null || turn.rejectedOperations.isEmpty())) {
                throw new IllegalStateException(
                        "Closed stream requires an identified message and a saved rejection");
            }
            if (turn.pending != null && "update".equals(turn.pending.kind())
                    && (!turn.streamClosed || !Objects.equals(turn.streamId, turn.pending.remoteId()))) {
                throw new IllegalStateException("Message update requires a confirmed closed stream");
            }
            if (turn.terminalNotice != null) {
                var notice = turn.terminalNotice;
                if (notice.goalGeneration() != turn.goalGeneration || notice.status() == null
                        || !notice.status().terminal() || notice.payloadDigest() == null
                        || !notice.payloadDigest().equals(turn.payloadDigest)
                        || notice.text() == null || notice.text().isBlank() || notice.text().length() > 1_000
                        || notice.bindingDigest() == null
                        || !notice.bindingDigest().matches("[a-f0-9]{64}")) {
                    throw new IllegalStateException("Corrupt terminal notice");
                }
            } else if (turn.terminalNoticeId != null) {
                throw new IllegalStateException("Terminal acknowledgement requires its saved notice");
            }
            if (turn.rejectedTerminalNotices == null || turn.rejectedTerminalNotices.size() > 3
                    || turn.terminalNoticeRetryAt < 0
                    || turn.terminalNoticeRetryAt > 0 && turn.rejectedTerminalNotices.isEmpty()) {
                throw new IllegalStateException("Corrupt terminal notice retry state");
            }
            for (var rejected : turn.rejectedTerminalNotices) {
                var operation = rejected == null ? null : rejected.operation();
                if (operation == null || turn.terminalNotice == null
                        || !id.equals(operation.executionId()) || turn.number != operation.turn()
                        || !turn.destination.equals(operation.destination())
                        || !"terminal_notice".equals(operation.kind()) || operation.remoteId() != null
                        || !turn.terminalNotice.text().equals(operation.text())
                        || !turn.terminalNotice.bindingDigest().equals(operation.answerDigest())
                        || rejected.errorCode() == null) {
                    throw new IllegalStateException("Corrupt rejected terminal notice");
                }
            }
            if (turn.pending != null && "terminal_notice".equals(turn.pending.kind())
                    && (turn.terminalNotice == null || turn.pending.remoteId() != null
                            || !turn.terminalNotice.text().equals(turn.pending.text())
                            || !turn.terminalNotice.bindingDigest().equals(turn.pending.answerDigest())
                            || turn.pending.blocks() != null || turn.pending.chunks() != null)) {
                throw new IllegalStateException("Terminal intent does not match its frozen notice");
            }
            if (turn.reportManifest.size() > 10
                    || turn.reportManifest.stream().anyMatch(
                            report -> report == null
                                    || report.goalGeneration() != turn.goalGeneration
                                    || report.reportId() == null || report.checksum() == null)
                    || turn.reportManifest.stream().map(OutputReportReference::reportId).distinct()
                            .count() != turn.reportManifest.size()) {
                throw new IllegalStateException("Corrupt report delivery manifest");
            }
            if (turn.pending != null) {
                boolean fileOperation = List.of("file_prepare", "file_upload", "file_complete")
                        .contains(turn.pending.kind());
                if (fileOperation != (turn.pending.file() != null) || fileOperation
                        && (turn.pending.file().report() == null
                                || !turn.reportManifest.contains(turn.pending.file().report()))) {
                    throw new IllegalStateException("File intent does not match the frozen manifest");
                }
            }
        }
    }
}
