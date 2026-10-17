package net.velocity.agent;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.json.JsonMapper;

import net.velocity.agent.persistence.PersistedJson;
import net.velocity.agent.state.AcceptedCall;
import net.velocity.agent.state.ExecutionSnapshot;
import net.velocity.agent.state.ExecutionStatus;
import net.velocity.inference.SystemPrompts;
import net.velocity.inference.objects.InferenceContent;
import net.velocity.inference.objects.InferenceMessage;

/**
 * Deterministic seed for a new task that continues a stopped one. No model request is made and the source
 * execution is never modified. The user's own request, corrections and instruction stay verbatim; the
 * saved progress travels as one bounded handoff marked as untrusted historical data.
 */
final class TaskContinuation {

    /** A model never finished its response in these states, so there is remaining work to continue. */
    static final Set<ExecutionStatus> CONTINUABLE = EnumSet.of(
            ExecutionStatus.partial,
            ExecutionStatus.failed,
            ExecutionStatus.blocked,
            ExecutionStatus.cancelled);

    private static final JsonMapper JSON = PersistedJson.builder().build();
    private static final int HANDOFF_CHARS = 48_000;
    private static final int ANSWER_CHARS = 8_000;
    private static final int ASSISTANT_TEXT_CHARS = 4_000;
    private static final int RECENT_CALLS = 24;
    private static final int INPUT_CHARS = 256;
    private static final int RESULT_CHARS = 512;

    record Seed(String request, String handoff) {}

    private TaskContinuation() {
    }

    static Seed prepare(ExecutionSnapshot source, String instruction) {
        var request = new StringBuilder(
                SystemPrompts.TASK_CONTINUATION_REQUEST.formatted(
                        source.executionId,
                        source.status.name(),
                        source.failure == null ? "not recorded" : source.failure,
                        source.originalRequest));
        if (!source.corrections.isEmpty()) {
            request.append("\n\n").append(SystemPrompts.TASK_CONTINUATION_CORRECTIONS);
            for (var correction : source.corrections) {
                request.append("\n- ").append(correction.text());
            }
        }
        if (instruction != null && !instruction.isBlank()) {
            request.append("\n\n").append(SystemPrompts.TASK_CONTINUATION_INSTRUCTION).append('\n')
                    .append(instruction.strip());
        }
        return new Seed(request.toString(), handoff(source));
    }

    private static String handoff(ExecutionSnapshot source) {
        int calls = Math.min(RECENT_CALLS, source.calls.size());
        try {
            while (true) {
                String encoded = JSON.writeValueAsString(envelope(source, calls));
                if (encoded.length() <= HANDOFF_CHARS || calls == 0) {
                    return encoded.length() <= HANDOFF_CHARS
                            ? encoded
                            : encoded.substring(0, HANDOFF_CHARS) + " [handoff truncated]";
                }
                calls = calls / 2;
            }
        } catch (Exception invalid) {
            throw new IllegalStateException("Continuation handoff cannot be encoded", invalid);
        }
    }

    private static Map<String, Object> envelope(ExecutionSnapshot source, int recentCalls) {
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("kind", "task_continuation_handoff");
        envelope.put("version", 1);
        envelope.put("content_trust", "untrusted_historical_data");
        envelope.put("notice", SystemPrompts.TASK_CONTINUATION_NOTICE);
        envelope.put("source_execution_id", source.executionId);
        envelope.put("source_status", source.status.name());
        envelope.put("source_stop_reason", source.failure);
        envelope.put("source_created_at", source.createdAt == null ? null : source.createdAt.toString());
        envelope.put("source_model_turns", source.modelTurns);
        envelope.put("blockers", List.copyOf(source.blockers));
        envelope.put("task_outcome", source.taskOutcome);
        envelope.put("final_answer_excerpt", excerpt(source.finalAnswer, ANSWER_CHARS));
        String assistant = lastAssistantText(source);
        if (assistant != null && !assistant.equals(source.finalAnswer)) {
            envelope.put("last_assistant_text_excerpt", excerpt(assistant, ASSISTANT_TEXT_CHARS));
        }
        envelope.put(
                "output_reports",
                source.outputReports.stream().map(
                        report -> fields(
                                "report_id",
                                report.reportId(),
                                "filename",
                                report.filename(),
                                "media_type",
                                report.mediaType(),
                                "coverage",
                                report.coverage()))
                        .toList());
        envelope.put("calls_total", source.calls.size());
        envelope.put("calls_omitted", source.calls.size() - recentCalls);
        envelope.put(
                "recent_calls",
                source.calls.stream().skip(source.calls.size() - recentCalls).map(TaskContinuation::call)
                        .toList());
        return envelope;
    }

    private static Map<String, Object> call(AcceptedCall call) {
        var settlement = call.settlement;
        return fields(
                "call_id",
                call.id,
                "tool",
                call.name,
                "input_excerpt",
                excerpt(JSON.valueToTree(call.input).toString(), INPUT_CHARS),
                "state",
                call.state.name(),
                "outcome",
                settlement == null ? "settlement_unavailable" : settlement.outcome(),
                "error",
                settlement != null && settlement.error(),
                "result_excerpt",
                settlement == null ? null : excerpt(settlement.preview(), RESULT_CHARS));
    }

    /** Visible text only; provider reasoning and opaque native state never cross into the new task. */
    private static String lastAssistantText(ExecutionSnapshot source) {
        for (int i = source.messages.size() - 1; i >= 0; i--) {
            var message = source.messages.get(i);
            if (message.role() != InferenceMessage.Role.ASSISTANT) {
                continue;
            }
            var text = new ArrayList<String>();
            for (var content : message.content()) {
                if (content instanceof InferenceContent.Text visible) {
                    text.add(visible.text());
                } else if (content instanceof InferenceContent.ProviderBlock block
                        && "text".equals(block.type())
                        && block.data().get("text") instanceof String visible) {
                    text.add(visible);
                }
            }
            if (!text.isEmpty()) {
                return String.join("\n", text);
            }
        }
        return null;
    }

    private static String excerpt(String value, int limit) {
        if (value == null) {
            return null;
        }
        return value.length() <= limit ? value : value.substring(0, limit) + " [excerpt]";
    }

    private static Map<String, Object> fields(Object... values) {
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < values.length; i += 2) {
            result.put((String) values[i], values[i + 1]);
        }
        return result;
    }
}
