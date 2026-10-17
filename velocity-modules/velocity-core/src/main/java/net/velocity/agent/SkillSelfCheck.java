package net.velocity.agent;

import java.util.Objects;

import net.velocity.agent.state.ExecutionSnapshot;
import net.velocity.agent.state.TaskPurpose;
import net.velocity.inference.SystemPrompts;
import net.velocity.inference.objects.InferenceContent;
import net.velocity.inference.objects.InferenceMessage;

/**
 * Hermes-style end-of-task skill maintenance. When a root task that loaded a skill produces its final
 * answer, one extra model turn asks whether a loaded skill needs a patch. The answer recorded before the
 * check is what gets delivered: the check turn exists only so {@code skill_patch} can run, and whatever
 * the model says afterwards never replaces the candidate answer. The notice is an ordinary user message,
 * so the snapshot shape is unchanged and recovery sees it like any other turn.
 */
final class SkillSelfCheck {

    static final String NOTICE = SystemPrompts.SKILL_SELF_CHECK_NOTICE;
    /** Below this much active time the check is skipped rather than risk a partial stop. */
    static final long MINIMUM_REMAINING_MILLIS = 60_000;

    private SkillSelfCheck() {
    }

    /** Whether to interpose the check before accepting this final answer. */
    static boolean applies(
            ExecutionSnapshot snapshot,
            boolean rootRun,
            boolean reporting,
            long remainingMillis) {
        if (!rootRun || reporting || snapshot.partialReporting != null || snapshot.monitoring != null
                || snapshot.purpose != TaskPurpose.generic || snapshot.cancellationRequested
                || remainingMillis < MINIMUM_REMAINING_MILLIS || performed(snapshot)) {
            return false;
        }
        return snapshot.calls.stream().anyMatch(
                call -> "skill_load".equals(call.name) && call.settlement != null
                        && !call.settlement.error());
    }

    /** The check runs at most once per task; the notice message is its durable marker. */
    static boolean performed(ExecutionSnapshot snapshot) {
        return snapshot.messages.stream().anyMatch(SkillSelfCheck::notice);
    }

    /**
     * The answer the model gave before the check: the last non-blank assistant text ahead of the notice.
     * Null when no check ran, so the caller uses the current response.
     */
    static String candidateAnswer(ExecutionSnapshot snapshot) {
        int notice = -1;
        for (int i = snapshot.messages.size() - 1; i >= 0; i--) {
            if (notice(snapshot.messages.get(i))) {
                notice = i;
                break;
            }
        }
        if (notice < 0) {
            return null;
        }
        for (int i = notice - 1; i >= 0; i--) {
            InferenceMessage message = snapshot.messages.get(i);
            if (message.role() != InferenceMessage.Role.ASSISTANT) {
                continue;
            }
            String text = text(message);
            if (!text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    private static boolean notice(InferenceMessage message) {
        return message.role() == InferenceMessage.Role.USER && message.content().size() == 1
                && message.content().getFirst() instanceof InferenceContent.Text text
                && NOTICE.equals(text.text());
    }

    private static String text(InferenceMessage message) {
        var text = new StringBuilder();
        for (var block : message.content()) {
            if (block instanceof InferenceContent.Text item) {
                text.append(Objects.requireNonNullElse(item.text(), ""));
            }
        }
        return text.toString();
    }
}
