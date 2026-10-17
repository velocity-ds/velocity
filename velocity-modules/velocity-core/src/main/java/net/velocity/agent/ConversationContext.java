package net.velocity.agent;

import java.util.*;

import net.velocity.agent.delegation.DelegationProfileRegistry;
import net.velocity.agent.state.*;
import net.velocity.agent.tools.DelegateWorkTool;
import net.velocity.inference.SystemPrompts;
import net.velocity.inference.objects.InferenceMessage;

/** Prepared history and trusted instructions; never dispatches or grants tool authority. */
final class ConversationContext {

    private final ToolRegistry tools;
    private final ContextHooks hooks;
    private final String childInstructions;
    private final List<String> childToolNames;
    private final String delegationGuidance;

    ConversationContext(
            ToolRegistry tools,
            ContextHooks hooks,
            DelegationProfileRegistry delegationProfiles) {
        this.tools = tools;
        this.hooks = hooks;
        this.delegationGuidance = delegationProfiles.parentGuidance();
        this.childInstructions = null;
        this.childToolNames = List.of();
    }

    ConversationContext(ToolRegistry tools, ChildRunRepository repository, String instructions) {
        this.tools = tools;
        this.hooks = new ContextHooks() {};
        this.delegationGuidance = null;
        this.childInstructions = Objects.requireNonNull(instructions);
        var child = repository.child();
        this.childToolNames = repository.childProfileToolNames(child.profile, child.profileVersion);
    }

    List<InferenceMessage> preparedMessages(ExecutionSnapshot snapshot) {
        if (snapshot.inference.compact() != null
                && Objects
                        .equals(snapshot.inference.compact().binding(), snapshot.activeInferenceBinding())) {
            var view = new ArrayList<>(snapshot.inference.compact().messages());
            view.addAll(
                    snapshot.messages.subList(
                            snapshot.inference.compact().sourceMessageCount(),
                            snapshot.messages.size()));
            return List.copyOf(view);
        }
        return uncompactedMessages(snapshot);
    }

    List<InferenceMessage> uncompactedMessages(ExecutionSnapshot snapshot) {
        if (snapshot.inference.transition() != null) {
            var context = new ArrayList<>(snapshot.inference.transition().handoffMessages());
            context.addAll(
                    snapshot.messages.subList(
                            snapshot.inference.transition().sourceMessageCount(),
                            snapshot.messages.size()));
            return List.copyOf(context);
        }
        return List.copyOf(hooks.prepare(snapshot, List.copyOf(snapshot.messages)));
    }

    String instructions(ExecutionSnapshot snapshot, boolean reporting) {
        if (childInstructions != null) {
            return childInstructions
                    + (reporting ? "\n" + SystemPrompts.EXECUTION_CAPACITY_INSTRUCTIONS : "");
        }
        String continuation = snapshot.inference.transition() == null
                ? ""
                : "\n" + SystemPrompts.CLOUD_CONTINUATION_INSTRUCTIONS;
        String reportingSuffix = snapshot.partialReporting != null
                ? "\n" + SystemPrompts.CONTEXT_LIMIT_REPORTING_INSTRUCTIONS
                : reporting ? "\n" + SystemPrompts.EXECUTION_CAPACITY_INSTRUCTIONS : "";
        if (snapshot.admissionContext != null) {
            return snapshot.admissionContext.instructions() + continuation + reportingSuffix;
        }
        String supplemental = hooks.supplementalInstructions(snapshot.scope);
        return SystemPrompts.AGENT_INSTRUCTIONS
                + (snapshot.monitoring == null && snapshot.purpose == TaskPurpose.generic
                        ? "\n" + SystemPrompts.TASK_OUTCOME_INSTRUCTIONS
                        : "")
                + (snapshot.instructions == null ? "" : "\n" + snapshot.instructions)
                + (supplemental == null ? "" : "\n" + supplemental)
                + (tools.find("delegate_work").filter(d -> d.handler() instanceof DelegateWorkTool.Handler)
                        .isPresent()
                                ? "\n" + delegationGuidance
                                : "")
                + continuation + reportingSuffix;
    }

    AdmissionContext freezeAdmission(ExecutionSnapshot snapshot, String learningContext) {
        return AdmissionContext.of(
                instructions(snapshot, false)
                        + (learningContext == null || learningContext.isBlank()
                                ? ""
                                : "\n" + learningContext),
                availableTools(snapshot));
    }

    List<ToolRegistry.Definition> availableTools(ExecutionSnapshot snapshot) {
        if (childInstructions == null && snapshot.admissionContext != null) {
            return snapshot.admissionContext.tools().stream().map(tool -> tool.resolve(tools)).toList();
        }
        return tools.definitions().stream()
                .filter(tool -> snapshot.monitoring == null || tool.monitoringAllowed(null))
                .filter(tool -> childInstructions == null || childToolNames.contains(tool.name())).toList();
    }
}
