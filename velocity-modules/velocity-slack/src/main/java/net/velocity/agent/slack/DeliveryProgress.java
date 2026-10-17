package net.velocity.agent.slack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import net.velocity.agent.state.*;

/** Projects only saved operational state. No model reasoning or tool-result bodies enter the activity area. */
final class DeliveryProgress {

    private static final Gson JSON = new Gson();
    private static final java.lang.reflect.Type STATES = new TypeToken<LinkedHashMap<String, Item>>() {}
            .getType();

    record Item(String type, String title, String status, String details, List<String> emittedDetails) {}

    record Batch(String chunks, String snapshot) {}

    private DeliveryProgress() {
    }

    static Batch initial(ExecutionSnapshot snapshot, String botName) {
        var desired = new LinkedHashMap<String, Item>();
        desired.put("plan", plan(botName));
        desired.put("execution", execution(snapshot, botName));
        return changes(desired, null).getFirst();
    }

    static List<Batch> updates(
            ExecutionSnapshot snapshot,
            String botName,
            String acknowledged,
            boolean answerDelivered) {
        var saved = read(acknowledged);
        var desired = new LinkedHashMap<String, Item>();
        String request = Objects
                .toString(snapshot.currentGoal, Objects.toString(snapshot.originalRequest, botName));
        desired.put("plan", plan(bound(request.replaceAll("\\s+", " ").strip(), 96)));
        desired.put("execution", execution(snapshot, botName, answerDelivered));
        for (AcceptedCall call : snapshot.calls) {
            String key = "tool_" + SlackControlBinding.digest(call.id);
            if (call.dispatchedAt == null && call.state != CallState.dispatched && !saved.containsKey(key)) {
                continue;
            }
            String status = "in_progress";
            String details = nonblank(call.progress, "Running tool");
            if (call.state == CallState.settled && call.settlement != null) {
                status = call.settlement.error() ? "error" : "complete";
                details = call.settlement.error()
                        ? interrupted(call.settlement) ? "Tool interrupted" : "Tool failed"
                        : "Finished";
            } else if (snapshot.cancellationRequested || snapshot.status == ExecutionStatus.cancelled) {
                status = "error";
                details = "Tool interrupted";
            }
            desired.put(key, task(nonblank(call.name, "Tool"), status, details));
        }
        return changes(desired, acknowledged);
    }

    private static boolean interrupted(Settlement settlement) {
        return Set.of("cancelled", "timeout", "superseded", "active_time_limit", "task_token_ceiling")
                .contains(settlement.outcome())
                || "uncertain".equals(settlement.outcome()) && settlement.preview() != null
                        && (settlement.preview().startsWith("Interrupted dispatch")
                                || settlement.preview().startsWith("Dispatch interrupted"));
    }

    private static Item execution(ExecutionSnapshot snapshot, String botName) {
        return execution(snapshot, botName, false);
    }

    private static Item execution(ExecutionSnapshot snapshot, String botName, boolean answerDelivered) {
        if (snapshot.cancellationRequested) {
            return task(
                    "Task stopped",
                    "error",
                    snapshot.status == ExecutionStatus.cancelled
                            ? "Task cancelled"
                            : "Cancellation requested; external operations may still be finishing");
        }
        return switch (snapshot.status) {
            case succeeded -> answerDelivered
                    ? task("Response complete", "complete", "Finished responding")
                    : task("Delivering response", "in_progress", "Response ready; delivering saved answer");
            case waiting_for_user ->
                task("Waiting for your reply", "in_progress", "Reply in this thread to continue");
            case cancelled -> task(
                    "Task stopped",
                    "error",
                    "Task cancelled; completed external effects were not rolled back");
            case failed, partial, blocked -> task("Task stopped", "error", explanation(snapshot));
            case running -> {
                var active = snapshot.calls.stream().filter(c -> c.state == CallState.dispatched).findFirst();
                String activity = active.map(c -> nonblank(c.progress, "Running " + c.name)).orElseGet(
                        () -> snapshot.modelTurns == 0 ? "Starting request" : "Preparing response");
                yield task(botName, "in_progress", activity);
            }
        };
    }

    private static String explanation(ExecutionSnapshot snapshot) {
        String reason = snapshot.failure;
        if ((reason == null || reason.isBlank()) && !snapshot.blockers.isEmpty()) {
            reason = String.join(", ", snapshot.blockers);
        }
        String description = switch (Objects.toString(reason, "")) {
            case "model_budget_limit", "tool_budget_limit" -> "Execution budget reached; work may remain";
            case "active_time_limit" -> "Execution time limit reached; work may remain";
            case "task_token_ceiling" -> "Task token ceiling reached; work may remain";
            case "context_limit" -> "Conversation context limit reached; work may remain";
            case "destination_inspection_required" ->
                "An external operation has an unknown outcome; destination inspection is required";
            case "operation_not_settled" -> "An external operation is still unsettled";
            case "automatic_resumption_disabled" -> "Automatic resumption is disabled";
            case "consecutive_unusable_responses" -> "The provider returned consecutive unusable responses";
            default -> nonblank(
                    reason,
                    snapshot.status == ExecutionStatus.partial
                            ? "Execution ended with incomplete work"
                            : snapshot.status == ExecutionStatus.blocked
                                    ? "Execution is blocked"
                                    : "Execution failed");
        };
        return bound(description, 256);
    }

    private static Item plan(String title) {
        return new Item("plan_update", bound(nonblank(title, "Request"), 256), null, null, List.of());
    }

    private static Item task(String title, String status, String details) {
        // Reserve one character for Slack's append-only details separator on subsequent updates.
        return new Item("task_update", bound(title, 256), status, bound(details, 255), List.of());
    }

    private static List<Batch> changes(Map<String, Item> desired, String acknowledged) {
        var saved = read(acknowledged);
        var batches = new ArrayList<Batch>();
        var chunks = new ArrayList<Map<String, Object>>();
        for (var entry : desired.entrySet()) {
            String id = entry.getKey();
            Item next = entry.getValue(), previous = saved.get(id);
            if (previous != null && Objects.equals(previous.title, next.title)
                    && Objects.equals(previous.status, next.status)
                    && Objects.equals(previous.details, next.details)) {
                continue;
            }
            var chunk = new LinkedHashMap<String, Object>();
            chunk.put("type", next.type);
            chunk.put("title", next.title);
            var emitted = new ArrayList<String>(previous == null ? List.of() : previous.emittedDetails);
            if ("task_update".equals(next.type)) {
                chunk.put("id", id);
                chunk.put("status", next.status);
                String details = next.details;
                if (previous != null && previous.details != null && details.startsWith(previous.details)) {
                    details = details.substring(previous.details.length()).strip();
                }
                var newLines = new ArrayList<String>();
                for (String line : details.split("\\R")) {
                    if (!line.isBlank() && !emitted.contains(line)) {
                        newLines.add(line);
                        emitted.add(line);
                    }
                }
                if (!newLines.isEmpty()) {
                    chunk.put(
                            "details",
                            bound(
                                    (previous == null || previous.emittedDetails.isEmpty() ? "" : "\n")
                                            + String.join("\n", newLines),
                                    256));
                }
            }
            saved.put(id, new Item(next.type, next.title, next.status, next.details, List.copyOf(emitted)));
            // Changes to details already seen need no external append, but their local comparison state can
            // ride along with the next real change. Never append an identical task just to move this cursor.
            if (previous == null || !Objects.equals(previous.title, next.title)
                    || !Objects.equals(previous.status, next.status)
                    || chunk.containsKey("details")) {
                chunks.add(chunk);
            }
            if (chunks.size() == 10) {
                batches.add(new Batch(JSON.toJson(chunks), JSON.toJson(saved)));
                chunks.clear();
            }
        }
        if (!chunks.isEmpty()) {
            batches.add(new Batch(JSON.toJson(chunks), JSON.toJson(saved)));
        }
        return batches;
    }

    private static LinkedHashMap<String, Item> read(String text) {
        if (text == null) {
            return new LinkedHashMap<>();
        }
        LinkedHashMap<String, Item> result = JSON.fromJson(text, STATES);
        if (result == null) {
            throw new IllegalStateException("Invalid acknowledged progress");
        }
        return result;
    }

    static String bound(String text, int characters) {
        int count = text.codePointCount(0, text.length());
        return count <= characters ? text : text.substring(0, text.offsetByCodePoints(0, characters));
    }

    private static String nonblank(String text, String fallback) {
        return text == null || text.isBlank() ? fallback : text;
    }
}
