package net.velocity.agent.delivery;

import java.util.Optional;

import net.velocity.agent.state.OutputReportReference;
import net.velocity.agent.state.TrustedScope;

/** An acknowledged external operation. No transport implementation may silently retry a write. */
public interface DeliveryTransport {

    record Destination(TrustedScope scope, String threadId) {}

    record Operation(
            String id,
            String executionId,
            long turn,
            Destination destination,
            String kind,
            String remoteId,
            String text,
            String blocks,
            String answerDigest,
            int offset,
            String chunks,
            String progressSnapshot,
            FilePayload file) {

        public Operation(
                String id,
                String executionId,
                long turn,
                Destination destination,
                String kind,
                String remoteId,
                String text,
                String blocks,
                String answerDigest,
                int offset,
                String chunks,
                String progressSnapshot) {
            this(
                    id,
                    executionId,
                    turn,
                    destination,
                    kind,
                    remoteId,
                    text,
                    blocks,
                    answerDigest,
                    offset,
                    chunks,
                    progressSnapshot,
                    null);
        }

        /** Retains the payload and stream mode of operations persisted before structured streaming. */
        public Operation(
                String id,
                String executionId,
                long turn,
                Destination destination,
                String kind,
                String remoteId,
                String text,
                String blocks,
                String answerDigest,
                int offset) {
            this(
                    id,
                    executionId,
                    turn,
                    destination,
                    kind,
                    remoteId,
                    text,
                    blocks,
                    answerDigest,
                    offset,
                    null,
                    null);
        }
    }

    /** Upload credentials stay in the private journal and never enter inference or operator metadata. */
    record FilePayload(OutputReportReference report, String uploadUrl) {}

    record Receipt(String operationId, String remoteId, String uploadUrl) {

        public Receipt(String operationId, String remoteId) {
            this(operationId, remoteId, null);
        }
    }

    /** A classified failure, never an invitation to retry a possibly applied write. */
    final class Failure extends Exception {

        private final boolean acknowledgedAbsent;
        private final boolean streamUsable;
        private final String errorCode;
        private final long retryAfterMillis;

        public Failure(String message, boolean acknowledgedAbsent, boolean streamUsable) {
            this(message, acknowledgedAbsent, streamUsable, null);
        }

        /** Keep remote diagnostics in a bounded machine code, never in the caller-supplied safe message. */
        public Failure(String message, boolean acknowledgedAbsent, boolean streamUsable, String errorCode) {
            this(message, acknowledgedAbsent, streamUsable, errorCode, 0);
        }

        public Failure(
                String message,
                boolean acknowledgedAbsent,
                boolean streamUsable,
                String errorCode,
                long retryAfterMillis) {
            super(message);
            this.acknowledgedAbsent = acknowledgedAbsent;
            this.streamUsable = streamUsable;
            this.errorCode = errorCode != null && errorCode.matches("[a-z0-9_]{1,80}")
                    ? errorCode
                    : "unknown_error";
            this.retryAfterMillis = Math.max(0, retryAfterMillis);
        }

        public boolean acknowledgedAbsent() {
            return acknowledgedAbsent;
        }

        public boolean streamUsable() {
            return streamUsable;
        }

        public String errorCode() {
            return errorCode;
        }

        public long retryAfterMillis() {
            return retryAfterMillis;
        }
    }

    Receipt execute(Operation operation) throws Exception;

    /** Positive evidence only: absence from a bounded inspection is not evidence of non-delivery. */
    default Optional<Receipt> inspect(Operation operation) throws Exception {
        return Optional.empty();
    }
}
