package net.velocity.agent.state;

import java.util.List;

import net.velocity.inference.objects.InferenceContent;
import net.velocity.inference.objects.InferenceMessage;

/** Internal admission request. External DTOs intentionally expose a much smaller surface. */
public record StartRequest(
        String request,
        String conversationId,
        TrustedScope scope,
        String instructions,
        List<InferenceMessage> history,
        List<InferenceContent> attachments,
        SourceTurn sourceTurn,
        ExecutionLimits limits,
        Boolean automaticResumption,
        List<SourceMessage> sourceMessages,
        MonitoringExecution monitoring,
        List<UploadedFile> uploads) {

    public StartRequest(
            String request,
            String conversationId,
            TrustedScope scope,
            String instructions,
            List<InferenceMessage> history,
            List<InferenceContent> attachments,
            SourceTurn sourceTurn,
            ExecutionLimits limits,
            Boolean automaticResumption,
            List<SourceMessage> sourceMessages,
            MonitoringExecution monitoring) {
        this(
                request,
                conversationId,
                scope,
                instructions,
                history,
                attachments,
                sourceTurn,
                limits,
                automaticResumption,
                sourceMessages,
                monitoring,
                List.of());
    }

    public StartRequest(
            String request,
            String conversationId,
            TrustedScope scope,
            String instructions,
            List<InferenceMessage> history,
            List<InferenceContent> attachments,
            SourceTurn sourceTurn,
            ExecutionLimits limits,
            Boolean automaticResumption,
            List<SourceMessage> sourceMessages) {
        this(
                request,
                conversationId,
                scope,
                instructions,
                history,
                attachments,
                sourceTurn,
                limits,
                automaticResumption,
                sourceMessages,
                null);
    }

    public StartRequest(
            String request,
            String conversationId,
            TrustedScope scope,
            String instructions,
            List<InferenceMessage> history,
            List<InferenceContent> attachments,
            SourceTurn sourceTurn,
            ExecutionLimits limits,
            Boolean automaticResumption) {
        this(
                request,
                conversationId,
                scope,
                instructions,
                history,
                attachments,
                sourceTurn,
                limits,
                automaticResumption,
                List.of());
    }

    public StartRequest {
        if (request == null || request.isBlank()) {
            throw new IllegalArgumentException("Request must not be blank");
        }
        if (scope == null) {
            throw new IllegalArgumentException("Trusted scope is required");
        }
        history = history == null ? List.of() : List.copyOf(history);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        sourceMessages = sourceMessages == null ? List.of() : List.copyOf(sourceMessages);
        uploads = uploads == null ? List.of() : List.copyOf(uploads);
        SourceMessage.validateThread(scope, sourceTurn, sourceMessages);
        if (monitoring != null && (!"scheduler".equals(scope.origin()) || sourceTurn == null
                || !monitoring.scheduleId().equals(sourceTurn.threadId())
                || !monitoring.occurrenceId().equals(sourceTurn.messageId()))) {
            throw new IllegalArgumentException("Monitoring requires its trusted scheduler source turn");
        }
        if (sourceTurn != null && (!sourceTurn.workspaceId().equals(scope.workspaceId())
                || !sourceTurn.channelId().equals(scope.channelId()))) {
            throw new IllegalArgumentException("Source turn must match trusted workspace and channel");
        }
    }
}
