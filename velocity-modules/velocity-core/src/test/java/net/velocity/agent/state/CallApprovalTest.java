package net.velocity.agent.state;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CallApprovalTest {

    private static final Instant REQUESTED = Instant.parse("2026-03-01T12:00:00Z");
    private static final Instant DECIDED = Instant.parse("2026-03-01T12:05:00Z");

    @Test
    void pendingApprovalsHoldTheCallUntilDecided() {
        var pending = CallApproval.pending(REQUESTED, "Send the report to #general");
        assertThat(pending.pending()).isTrue();
        assertThat(pending.state()).isEqualTo(CallApproval.State.pending);
        assertThat(pending.requestedAt()).isEqualTo(REQUESTED);
        assertThat(pending.decidedAt()).isNull();
        assertThat(pending.summary()).isEqualTo("Send the report to #general");
        assertThat(CallApproval.pending(REQUESTED).summary()).isNull();
    }

    @Test
    void decisionsKeepTheRequestAndSummaryAndRecordTheChannel() {
        var pending = CallApproval.pending(REQUESTED, "Delete the file");
        var approved = pending.decided(true, DECIDED, "slack");
        assertThat(approved.state()).isEqualTo(CallApproval.State.approved);
        assertThat(approved.pending()).isFalse();
        assertThat(approved.requestedAt()).isEqualTo(REQUESTED);
        assertThat(approved.decidedAt()).isEqualTo(DECIDED);
        assertThat(approved.decidedVia()).isEqualTo("slack");
        assertThat(approved.summary()).isEqualTo("Delete the file");
        assertThat(pending.decided(false, DECIDED, "console").state()).isEqualTo(CallApproval.State.declined);
    }

    @Test
    void summariesAreBoundedWithAnEllipsis() {
        String summary = "x".repeat(CallApproval.MAX_SUMMARY_CHARS + 500);
        var approval = CallApproval.pending(REQUESTED, summary);
        assertThat(approval.summary()).hasSize(CallApproval.MAX_SUMMARY_CHARS).endsWith("…");
        assertThat(CallApproval.pending(REQUESTED, "x".repeat(CallApproval.MAX_SUMMARY_CHARS)).summary())
                .hasSize(CallApproval.MAX_SUMMARY_CHARS).doesNotEndWith("…");
    }
}
