package net.velocity.agent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.json.JsonMapper;

import net.velocity.agent.persistence.PersistedJson;
import net.velocity.agent.state.CallState;
import net.velocity.agent.state.ExecutionSnapshot;
import net.velocity.inference.SystemPrompts;
import net.velocity.inference.objects.InferenceMessage;
import net.velocity.inference.objects.InferenceToolCalls;

/** Bounded extractive context: no model request, inferred facts, or authority from tool output. */
final class ConversationCompactor {

    private static final JsonMapper JSON = PersistedJson.builder().build();
    private static final int RECENT_MESSAGES = 6;
    private static final int SUMMARY_CHARS = 16_384;

    record Result(List<InferenceMessage> messages, String evidence, String failure) {

        boolean supported() {
            return failure == null;
        }
    }

    static Result prepare(
            ExecutionSnapshot snapshot,
            List<InferenceMessage> prepared,
            String artifactReference) {
        if (snapshot.calls.stream().anyMatch(call -> call.state != CallState.settled)
                || snapshot.childRuns.values().stream().anyMatch(child -> !child.status.terminal())) {
            return failed("unsettled_work");
        }
        int cut;
        try {
            cut = balancedCut(prepared);
        } catch (IllegalArgumentException unsupported) {
            return failed("unsupported_native_interaction");
        }
        if (cut <= 1) {
            return failed("no_safe_compaction_boundary");
        }
        try {
            var evidence = new LinkedHashMap<String, Object>();
            evidence.put("content_trust", "untrusted_historical_data");
            evidence.put("source_message_count", snapshot.messages.size());
            evidence.put("source_history_generation", snapshot.historyGeneration);
            var messageIndex = new ArrayList<Map<String, Object>>();
            for (int sequence = 1; sequence <= snapshot.messages.size(); sequence++) {
                var message = snapshot.messages.get(sequence - 1);
                messageIndex.add(
                        Map.of(
                                "message_sequence",
                                sequence,
                                "role",
                                message.role().name(),
                                "content_types",
                                message.content().stream().map(content -> content.getClass().getSimpleName())
                                        .toList()));
            }
            evidence.put("canonical_messages", messageIndex);
            evidence.put("calls", snapshot.calls.stream().map(call -> {
                var entry = new LinkedHashMap<String, Object>();
                entry.put("call_id", call.id);
                entry.put("tool", call.name);
                entry.put("input", call.input);
                entry.put("goal_generation", call.goalGeneration);
                entry.put("settlement", call.settlement);
                return entry;
            }).toList());
            evidence.put("reports", snapshot.outputReports);
            var summary = new LinkedHashMap<String, Object>();
            summary.put("kind", "extractive_conversation_summary");
            summary.put("content_trust", "untrusted_historical_data");
            summary.put(
                    "notice",
                    SystemPrompts.COMPACT_CONTEXT_NOTICE);
            summary.put("current_goal", snapshot.currentGoal);
            summary.put("canonical_message_count", snapshot.messages.size());
            summary.put("evidence_index_artifact", artifactReference);
            summary.put("blockers", snapshot.blockers);
            summary.put("reports", snapshot.outputReports);
            summary.put(
                    "recent_settlements",
                    snapshot.calls.stream().skip(Math.max(0, snapshot.calls.size() - 16)).map(call -> {
                        var entry = new LinkedHashMap<String, Object>();
                        entry.put("call_id", call.id);
                        entry.put("tool", call.name);
                        entry.put("input_excerpt", excerpt(JSON.valueToTree(call.input).toString(), 256));
                        entry.put("outcome", call.settlement.outcome());
                        entry.put("error", call.settlement.error());
                        entry.put("artifact", call.settlement.artifact());
                        entry.put("result_excerpt", excerpt(call.settlement.preview(), 384));
                        return entry;
                    }).toList());
            String encoded = JSON.writeValueAsString(summary);
            if (encoded.length() > SUMMARY_CHARS) {
                return failed("summary_capacity");
            }
            var result = new ArrayList<InferenceMessage>();
            // Preserve original user constraints/corrections verbatim; never summarize away authority.
            for (var message : prepared.subList(0, cut)) {
                if (message.role() == InferenceMessage.Role.USER) {
                    var userContent = message.content().stream()
                            .filter(
                                    content -> !(InferenceToolCalls
                                            .decode(content) instanceof InferenceToolCalls.Result))
                            .toList();
                    if (!userContent.isEmpty()) {
                        result.add(new InferenceMessage(message.role(), userContent));
                    }
                }
            }
            result.add(InferenceMessage.user(encoded));
            result.addAll(prepared.subList(cut, prepared.size()));
            return new Result(List.copyOf(result), JSON.writeValueAsString(evidence), null);
        } catch (Exception invalid) {
            return failed("summary_encoding_failed");
        }
    }

    /** Only cut between complete native exchanges, including parallel calls/results. */
    static int balancedCut(List<InferenceMessage> messages) {
        var pending = new HashSet<String>();
        int cut = 0;
        for (int i = 0; i < messages.size(); i++) {
            for (var content : messages.get(i).content()) {
                var interaction = InferenceToolCalls.decode(content);
                if (interaction instanceof InferenceToolCalls.Call call) {
                    add(pending, call.id());
                } else if (interaction instanceof InferenceToolCalls.Result result) {
                    remove(pending, result.id());
                } else if (interaction instanceof InferenceToolCalls.UnsupportedAction) {
                    throw new IllegalArgumentException("Unknown native interaction");
                }
            }
            if (pending.isEmpty() && i + 1 <= messages.size() - RECENT_MESSAGES) {
                cut = i + 1;
            }
        }
        if (!pending.isEmpty()) {
            throw new IllegalArgumentException("Unsettled native interaction");
        }
        return cut;
    }

    private static void add(Set<String> pending, String id) {
        if (InferenceToolCalls.registerIdentity(id, pending) != null) {
            throw new IllegalArgumentException("Invalid native call");
        }
    }

    private static void remove(Set<String> pending, String id) {
        if (!pending.remove(id)) {
            throw new IllegalArgumentException("Unmatched native result");
        }
    }

    private static String excerpt(String value, int limit) {
        if (value == null) {
            return "";
        }
        return value.length() <= limit ? value : value.substring(0, limit) + " [excerpt; see saved evidence]";
    }

    private static Result failed(String reason) {
        return new Result(List.of(), null, reason);
    }
}
