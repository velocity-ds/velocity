package net.velocity.agent.browser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import net.velocity.agent.AuthorizationPolicy;
import net.velocity.agent.CallProvenance;
import net.velocity.agent.ToolRegistry;
import net.velocity.agent.persistence.ExecutionRepository;
import net.velocity.agent.secrets.CredentialException;
import net.velocity.agent.secrets.CredentialService;
import net.velocity.agent.security.BrowserDestinationPolicy;
import net.velocity.agent.security.BrowserRejectedException;
import net.velocity.agent.state.CallState;
import net.velocity.agent.state.ExecutionStatus;

/** Vault authority and private Browser Use input; plaintext has no model tool contract. */
final class BrowserCredentialOperations {

    private final BrowserService browser;
    private final ExecutionRepository repository;
    private final CredentialService credentials;
    private AuthorizationPolicy authorization;
    private ToolRegistry registry;

    BrowserCredentialOperations(
            BrowserService browser,
            ExecutionRepository repository,
            CredentialService credentials) {
        this.browser = browser;
        this.repository = repository;
        this.credentials = credentials;
    }

    void setAuthorization(AuthorizationPolicy policy, ToolRegistry tools) {
        authorization = Objects.requireNonNull(policy);
        registry = Objects.requireNonNull(tools);
    }

    Map<String, Object> find(ToolRegistry.Context context, Map<String, Object> input) throws Exception {
        CredentialTools.validateInput("credential_find", input);
        return browser.withSession(context, input, view -> {
            requireCall(context, input, "credential_find");
            String site = observedSite(view, input);
            try {
                var found = credentials.find(context.scope(), site, (String) input.get("account_label"));
                var matches = found.credentials().stream()
                        .map(
                                item -> Map.<String, Object>of(
                                        "credential_id",
                                        item.credentialId(),
                                        "revision",
                                        item.revision(),
                                        "service",
                                        item.service(),
                                        "account_label",
                                        item.accountLabel(),
                                        "credential_origins",
                                        item.credentialOrigins(),
                                        "post_login_origins",
                                        item.postLoginOrigins(),
                                        "application_origins",
                                        item.applicationOrigins(),
                                        "callback_urls",
                                        item.callbackUrls(),
                                        "resource_origins",
                                        item.resourceOrigins()))
                        .toList();
                return Map.of(
                        "status",
                        found.status(),
                        "matches",
                        matches,
                        "action_outcome",
                        "none",
                        "content_trust",
                        "untrusted_data");
            } catch (CredentialException unavailable) {
                throw unavailable(unavailable);
            }
        });
    }

    Map<String, Object> fill(ToolRegistry.Context context, Map<String, Object> input) throws Exception {
        return perform(context, input, true);
    }

    Map<String, Object> submit(ToolRegistry.Context context, Map<String, Object> input) throws Exception {
        return perform(context, input, false);
    }

    private Map<String, Object> perform(ToolRegistry.Context context, Map<String, Object> input, boolean fill)
            throws Exception {
        String tool = fill ? "browser_fill_credential" : "browser_submit_login";
        CredentialTools.validateInput(tool, input);
        return browser.withSession(context, input, view -> {
            var provenance = requireCall(context, input, tool);
            String site = observedSite(view, input), id = (String) input.get("credential_id");
            var targets = new java.util.LinkedHashMap<String, BrowserService.ElementBinding>();
            for (String field : fill ? List.of("username_index", "password_index") : List.of("index")) {
                if (!input.containsKey(field)) {
                    continue;
                }
                var target = view.element(((Number) input.get(field)).intValue());
                if (!target.mainFrame() || fill && !credentialField(field, target)) {
                    throw rejected(
                            "stale_target",
                            "Use the appropriate main-page field from the current browser state.");
                }
                targets.put(field, target);
            }
            long revision = ((Number) input.get("revision")).longValue();
            String outcome = "uncertain_effect";
            try {
                CredentialService.Metadata metadata;
                try {
                    metadata = credentials.requireUsable(context.scope(), id, revision);
                } catch (CredentialException unavailable) {
                    throw unavailable(unavailable);
                }
                // A retained observation does not prove that scripts have kept the page or field
                // unchanged. Refresh and resolve the same native nodes before private dispatch.
                view.callRaw("browser_state", Map.of());
                if (view.lastSnapshot() == null
                        || !site.equals(BrowserDestinationPolicy.origin(view.lastSnapshot().pageUrl()))) {
                    throw rejected(
                            "stale_observation",
                            "The website changed since the observed login state.");
                }
                if (!metadata.credentialOrigins().contains(site)) {
                    throw rejected(
                            "credential_origin_denied",
                            "The actual observed page is not a saved credential receiver.");
                }
                var indices = new java.util.LinkedHashMap<String, Integer>();
                targets.forEach((field, target) -> indices.put(field, view.elementIndex(target)));
                Runnable authorityCheck = () -> checkNetwork(context, provenance, metadata, tool);
                if (fill) {
                    try {
                        credentials.withCredential(context.scope(), id, revision, pair -> {
                            requireCall(context, input, tool);
                            view.rememberSecret(pair.username());
                            view.rememberSecret(pair.password());
                            view.networkGuard().useCredential(
                                    metadata,
                                    authorityCheck,
                                    pair.username(),
                                    pair.password());
                            var fields = new ArrayList<Map<String, Object>>();
                            for (var entry : targets.entrySet()) {
                                boolean username = entry.getKey().equals("username_index");
                                var target = entry.getValue();
                                fields.add(
                                        Map.of(
                                                "index",
                                                indices.get(entry.getKey()),
                                                "kind",
                                                username ? "username" : "password",
                                                "value",
                                                username ? pair.username() : pair.password(),
                                                "backend_node_id",
                                                target.backendNodeId(),
                                                "frame_id",
                                                target.frameId(),
                                                "target_id",
                                                target.targetId()));
                            }
                            view.fillPrivate(site, fields);
                            return null;
                        });
                    } catch (CredentialException unavailable) {
                        throw unavailable(unavailable);
                    }
                } else {
                    if (!view.networkGuard().credentialMatches(id, revision)) {
                        throw rejected(
                                "credential_not_filled",
                                "Fill the same saved credential in this browser before submitting.");
                    }
                    authorityCheck.run();
                    view.networkGuard().beginSubmission(authorityCheck);
                    try {
                        requireSuccess(view.callRaw("browser_click", Map.of("index", indices.get("index"))));
                        if (view.networkGuard().submissionNavigationRejected()) {
                            throw new ToolRegistry.UncertainToolException(
                                    Map.of(
                                            "capability",
                                            "saved_credential_login",
                                            "reason",
                                            "policy_rejected",
                                            "external_effect",
                                            "unknown"));
                        }
                    } finally {
                        view.networkGuard().endSubmission();
                    }
                }
                outcome = fill ? "filled" : "submitted";
                return Map.of(
                        "status",
                        outcome,
                        "action_outcome",
                        "completed",
                        "content_trust",
                        "untrusted_data",
                        "session_id",
                        view.id(),
                        "generation",
                        view.generation());
            } catch (BrowserRejectedException rejected) {
                outcome = rejected.code();
                throw rejected;
            } catch (ToolRegistry.UncertainToolException uncertain) {
                throw new CredentialOperationException(
                        fill
                                ? CredentialOperationException.Phase.command_dispatch
                                : CredentialOperationException.Phase.submit_click,
                        CredentialOperationException.reason(uncertain));
            } catch (RuntimeException failure) {
                throw new CredentialOperationException(
                        fill
                                ? CredentialOperationException.Phase.command_dispatch
                                : CredentialOperationException.Phase.submit_click,
                        CredentialOperationException.reason(failure));
            } finally {
                credentials.auditUse(
                        context.scope(),
                        id,
                        revision,
                        context.executionId(),
                        context.acceptedCallId(),
                        outcome);
            }
        });
    }

    private static void requireSuccess(Map<String, Object> result) {
        if (Boolean.TRUE.equals(result.get("is_error")) || !"ok".equals(result.get("status"))) {
            throw new ToolRegistry.UncertainToolException(
                    Map.of(
                            "capability",
                            "saved_credential_login",
                            "reason",
                            "browser_tool_error",
                            "external_effect",
                            "unknown"));
        }
    }

    private static boolean credentialField(String field, BrowserService.ElementBinding target) {
        if (field.equals("password_index")) {
            return target.tag().equals("input") && target.type().equals("password");
        }
        return target.tag().equals("textarea") || target.tag().equals("input")
                && List.of("", "text", "email").contains(target.type());
    }

    private static String observedSite(BrowserService.SessionView view, Map<String, Object> input) {
        if (view.lastSnapshot() == null || !Objects.equals(view.lastSnapshotId(), input.get("state_id"))) {
            throw rejected("stale_observation", "A current retained browser state is required.");
        }
        return BrowserDestinationPolicy.origin(view.lastSnapshot().pageUrl());
    }

    private CallProvenance.Resolved requireCall(
            ToolRegistry.Context context,
            Map<String, Object> input,
            String tool) {
        if (repository == null || credentials == null || authorization == null || registry == null) {
            throw rejected("credential_unavailable", "Saved credential use is unavailable.");
        }
        if (context.childRunId() != null) {
            throw rejected("credential_denied", "Delegated work cannot use saved credentials.");
        }
        CallProvenance.Resolved current;
        try {
            current = CallProvenance.current(repository, context);
        } catch (CallProvenance.Rejected invalid) {
            throw rejected(invalid.status(), "Saved credential call is no longer current.");
        }
        var call = current.call();
        if (current.execution().monitoring != null || call.state != CallState.dispatched
                || !tool.equals(call.name)
                || !Objects.equals(
                        call.binding,
                        AuthorizationPolicy.binding(context.scope(), call.id, tool, input))
                || !Objects.equals(
                        call.binding,
                        AuthorizationPolicy.binding(context.scope(), call.id, call.name, call.input))) {
            throw rejected(
                    "credential_denied",
                    "An exact authorized dispatched credential call is required.");
        }
        if (authorization.authorize(
                context.scope(),
                registry.find(tool).orElseThrow(),
                call) != AuthorizationPolicy.Decision.allow) {
            throw rejected("credential_denied", "Saved credential use is not authorized.");
        }
        if (!tool.equals("credential_find")) {
            requireNoUncertainAttempt(current);
        }
        return current;
    }

    private void checkNetwork(
            ToolRegistry.Context context,
            CallProvenance.Resolved original,
            CredentialService.Metadata metadata,
            String tool) {
        context.cancellation().check();
        var current = repository.get(context.executionId());
        if (!current.scope.equals(context.scope()) || current.cancellationRequested
                || current.status != ExecutionStatus.running
                || current.goalGeneration != original.execution().goalGeneration || current.monitoring != null
                || repository.inbox(context.executionId()).stream()
                        .anyMatch(item -> !current.consumedInboxIds.contains(item.id()))) {
            throw rejected("stale_credential_context", "Saved credential authority is no longer active.");
        }
        if (authorization.authorize(
                context.scope(),
                registry.find(tool).orElseThrow(),
                original.call()) != AuthorizationPolicy.Decision.allow) {
            throw rejected("credential_denied", "Saved credential authorization was revoked.");
        }
        credentials.requireUsable(context.scope(), metadata.credentialId(), metadata.revision());
    }

    private static void requireNoUncertainAttempt(CallProvenance.Resolved current) {
        for (var previous : current.execution().calls) {
            if (previous.id.equals(current.call().id)) {
                break;
            }
            if (previous.goalGeneration != current.execution().goalGeneration
                    || previous.state == CallState.accepted
                    || !SetNames.CREDENTIAL_MUTATIONS.contains(previous.name)) {
                continue;
            }
            if (previous.settlement != null && "uncertain".equals(previous.settlement.outcome())) {
                throw rejected(
                        "credential_attempt_stopped",
                        "A previous saved credential effect is uncertain. Do not repeat it.");
            }
        }
    }

    private static final class SetNames {

        private static final java.util.Set<String> CREDENTIAL_MUTATIONS = java.util.Set
                .of("browser_fill_credential", "browser_submit_login");
    }

    private static BrowserRejectedException unavailable(CredentialException exception) {
        return rejected(exception.status(), "The saved credential is unavailable for this request.");
    }

    private static BrowserRejectedException rejected(String code, String message) {
        return new BrowserRejectedException(code, message);
    }
}
