package net.velocity.agent.browser;

import java.util.Map;

import net.velocity.agent.ToolRegistry;
import net.velocity.agent.security.BrowserRejectedException;

/** Safe machine diagnostics survive durable settlement; remote exception text never does. */
final class CredentialOperationException extends ToolRegistry.UncertainToolException {

    enum Phase {
        command_dispatch, submit_click
    }

    enum Reason {
        deadline_exceeded, transport_or_browser_failure, policy_rejected
    }

    CredentialOperationException(Phase phase, Reason reason) {
        super(
                Map.of(
                        "capability",
                        "saved_credential_login",
                        "phase",
                        phase.name(),
                        "reason",
                        reason.name(),
                        "submission_dispatch_started",
                        "unknown",
                        "submission_response_received",
                        "unknown",
                        "external_effect",
                        "unknown"));
    }

    static Reason reason(RuntimeException failure) {
        if (failure instanceof java.util.concurrent.CompletionException
                && failure.getCause() instanceof java.util.concurrent.TimeoutException) {
            return Reason.deadline_exceeded;
        }
        if (failure instanceof ToolRegistry.UncertainToolException uncertain) {
            if ("deadline_exceeded".equals(uncertain.diagnostic().get("reason"))) {
                return Reason.deadline_exceeded;
            }
            if ("policy_rejected".equals(uncertain.diagnostic().get("reason"))) {
                return Reason.policy_rejected;
            }
        }
        return failure instanceof BrowserRejectedException
                ? Reason.policy_rejected
                : Reason.transport_or_browser_failure;
    }
}
