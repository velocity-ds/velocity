package net.velocity.agent;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import net.velocity.agent.config.DelegationProperties;
import net.velocity.agent.delegation.ChildContextHooks;
import net.velocity.agent.delegation.ChildReferenceValidator;
import net.velocity.agent.delegation.DelegationProfileRegistry;
import net.velocity.agent.persistence.ExecutionRepository;
import net.velocity.agent.state.*;
import net.velocity.inference.InferenceProfileRegistry;
import net.velocity.inference.SystemPrompts;
import net.velocity.inference.objects.*;

/** One bounded, synchronous child, persisted inside its parent's execution aggregate. */
final class DelegationService {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final ExecutionRepository repository;
    private final ModelClient model;
    private final InferenceProfileRegistry profiles;
    private final DelegationProfileRegistry delegationProfiles;
    private final ConversationContext parentContext;
    private final ToolRegistry tools;
    private final AuthorizationPolicy authorization;
    private final DelegationProperties properties;
    private final ChildContextHooks childHooks;
    private final ChildReferenceValidator references;
    private final long taskTokenCeiling;
    private java.util.function.Consumer<ExecutionSnapshot> scheduledValidator = snapshot -> {
        throw new SecurityException("Schedule association verifier unavailable");
    };

    void setScheduledValidator(java.util.function.Consumer<ExecutionSnapshot> validator) {
        scheduledValidator = validator;
    }

    DelegationService(
            ExecutionRepository repository,
            ModelClient model,
            ToolRegistry tools,
            AuthorizationPolicy authorization,
            DelegationProperties properties,
            ChildContextHooks childHooks,
            ChildReferenceValidator references,
            InferenceProfileRegistry profiles,
            DelegationProfileRegistry delegationProfiles,
            ConversationContext parentContext,
            long taskTokenCeiling) {
        this.taskTokenCeiling = taskTokenCeiling;
        this.repository = repository;
        this.model = model;
        this.tools = tools;
        this.authorization = authorization;
        this.parentContext = parentContext;
        this.properties = properties;
        this.childHooks = childHooks;
        this.references = references;
        this.profiles = profiles;
        this.delegationProfiles = delegationProfiles;
    }

    /** State shared by the ordered steps of one delegation wrapper execution. */
    private static final class Delegation {

        final String rootId;
        final AcceptedCall wrapper;
        final CancellationHandle cancellation;
        final long deadline;
        final boolean resume;
        ExecutionSnapshot root;
        String parentInstructions;
        List<InferenceMessage> parentMessages;
        List<ToolRegistry.Definition> parentDefinitions;
        String childId;
        ToolRegistry.Context context;
        int resultLimit;
        ChildRun child;
        ModelClient selectedModel;
        String profile;

        Delegation(
                String rootId,
                AcceptedCall wrapper,
                CancellationHandle cancellation,
                long deadline,
                boolean resume) {
            this.rootId = rootId;
            this.wrapper = wrapper;
            this.cancellation = cancellation;
            this.deadline = deadline;
            this.resume = resume;
        }
    }

    /**
     * Runs the wrapper as ordered steps. Each refusal step returns a result to stop with or empty to
     * continue, and {@link Optional#or} evaluates them lazily in this order.
     */
    Object execute(
            String rootId,
            AcceptedCall wrapper,
            CancellationHandle cancellation,
            long deadline,
            boolean resume) {
        var d = new Delegation(rootId, wrapper, cancellation, deadline, resume);
        d.root = repository.get(rootId);
        d.parentInstructions = parentContext.instructions(d.root, false);
        d.parentMessages = parentContext.preparedMessages(d.root);
        d.parentDefinitions = parentContext.availableTools(d.root);
        d.childId = ChildRun.id(rootId, wrapper.id, wrapper.binding, wrapper.goalGeneration);
        d.context = ToolRegistry.Context.builder()
                .executionId(rootId)
                .scope(d.root.scope)
                .cancellation(cancellation)
                .acceptedCall(wrapper.id, wrapper.binding)
                .build();
        d.resultLimit = Math.min(properties.maxResultBytes(), d.root.limits.outputPreviewChars());
        d.child = d.root.childRuns.get(d.childId);
        return refuseSuperseded(d)
                .or(() -> reuseCompletedResult(d))
                .or(() -> resolveChildModel(d))
                .or(() -> requireAuthorization(d))
                .or(() -> requireDelegationEnabled(d))
                .or(() -> requireProfile(d))
                .or(() -> admitChild(d))
                .orElseGet(() -> runChild(d));
    }

    private Optional<Object> refuseSuperseded(Delegation d) {
        if (!current(d.root, d.wrapper)) {
            if (d.child != null && !d.child.status.terminal()) {
                endWithoutDispatch(d.rootId, d.childId, "superseded");
            }
            var result = failure(
                    d.childId,
                    "superseded",
                    "The assignment is no longer current",
                    d.resultLimit);
            if (d.child != null && d.child.result == null) {
                finishExisting(d.rootId, d.childId, result);
            }
            return Optional.of(result);
        }
        return Optional.empty();
    }

    private Optional<Object> reuseCompletedResult(Delegation d) {
        if (d.child != null && d.child.result != null) {
            return Optional.of(parse(d.child.result));
        }
        return Optional.empty();
    }

    private Optional<Object> resolveChildModel(Delegation d) {
        try {
            d.selectedModel = modelFor(
                    d.child == null ? d.root.activeInferenceBinding() : d.child.inferenceBinding);
        } catch (IllegalStateException unavailable) {
            if (d.child != null) {
                endWithoutDispatch(d.rootId, d.childId, "inference_profile_unavailable_or_incompatible");
            }
            return Optional.of(
                    finishExisting(
                            d.rootId,
                            d.childId,
                            failure(
                                    d.childId,
                                    "unavailable",
                                    "The child's pinned inference profile is unavailable or incompatible",
                                    d.resultLimit)));
        }
        return Optional.empty();
    }

    private Optional<Object> requireAuthorization(Delegation d) {
        var definition = tools.find(Objects.toString(d.wrapper.name, ""));
        boolean authorized = definition.isPresent() && definition.get().trusted()
                && definition.get()
                        .handler() instanceof net.velocity.agent.tools.DelegateWorkTool.Handler
                && authorization.authorize(
                        d.root.scope,
                        definition.get(),
                        d.wrapper) == AuthorizationPolicy.Decision.allow;
        if (authorized) {
            try {
                JsonSchema.validate(definition.get().inputSchema(), d.wrapper.input);
            } catch (IllegalArgumentException invalid) {
                authorized = false;
            }
        }
        if (!authorized && d.resume) {
            if (d.child != null) {
                endWithoutDispatch(d.rootId, d.childId, "delegation_denied");
            }
            return Optional.of(
                    finishExisting(
                            d.rootId,
                            d.childId,
                            failure(
                                    d.childId,
                                    "unavailable",
                                    "Delegation is no longer authorized",
                                    d.resultLimit)));
        }
        return Optional.empty();
    }

    private Optional<Object> requireDelegationEnabled(Delegation d) {
        if (!properties.enabled() || d.root.monitoring != null) {
            if (d.child != null) {
                endWithoutDispatch(d.rootId, d.childId, "delegation_disabled");
            }
            return Optional.of(
                    finishExisting(
                            d.rootId,
                            d.childId,
                            failure(
                                    d.childId,
                                    "unavailable",
                                    "Delegation is disabled for this execution",
                                    d.resultLimit)));
        }
        return Optional.empty();
    }

    private Optional<Object> requireProfile(Delegation d) {
        d.profile = Objects.toString(d.wrapper.input.get("profile"), "");
        try {
            delegationProfiles.resolve(d.profile, DelegationProfileRegistry.VERSION, tools);
        } catch (IllegalArgumentException | IllegalStateException unavailable) {
            if (d.child != null) {
                endWithoutDispatch(d.rootId, d.childId, "profile_unavailable");
            }
            return Optional.of(
                    finishExisting(
                            d.rootId,
                            d.childId,
                            failure(
                                    d.childId,
                                    "unavailable",
                                    "Required profile capabilities are unavailable",
                                    d.resultLimit)));
        }
        return Optional.empty();
    }

    /** Persists the child run on first execution; an existing child is resumed as is. */
    private Optional<Object> admitChild(Delegation d) {
        if (d.child != null) {
            return Optional.empty();
        }
        String rootId = d.rootId;
        var wrapper = d.wrapper;
        String childId = d.childId;
        String profile = d.profile;
        long deadline = d.deadline;
        int resultLimit = d.resultLimit;
        if (!d.resume) {
            return Optional.of(
                    failure(
                            childId,
                            "interrupted",
                            "Automatic delegation resumption is disabled",
                            resultLimit));
        }
        try {
            var supplied = referenceList(wrapper.input.getOrDefault("references", List.of()));
            references.validateChildReferences(d.context, supplied);
            repository.update(rootId, -1, state -> {
                if (!current(state, wrapper)) {
                    throw new ChildRunRepository.CapacityException("superseded");
                }
                if (state.childRuns.containsKey(childId)) {
                    return;
                }
                if (state.childRuns.size() >= Math.min(state.maxChildRuns, properties.maxChildren())
                        || state.childRuns.values().stream().anyMatch(c -> !c.status.terminal())) {
                    throw new ChildRunRepository.CapacityException("child_count_limit");
                }
                long millis = Math.max(
                        0,
                        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                if (millis < 1 || resultLimit < 512) {
                    throw new ChildRunRepository.CapacityException("delegation_deadline_or_result_capacity");
                }
                var admitted = new ChildRun();
                admitted.id = childId;
                admitted.rootExecutionId = rootId;
                admitted.parentCallId = wrapper.id;
                admitted.parentCallBinding = wrapper.binding;
                admitted.goalGeneration = wrapper.goalGeneration;
                admitted.profile = profile;
                admitted.inferenceBinding = state.activeInferenceBinding();
                admitted.objective = (String) wrapper.input.get("objective");
                admitted.context = (String) wrapper.input.get("context");
                admitted.references = supplied;
                admitted.createdAt = Instant.now();
                admitted.updatedAt = admitted.createdAt;
                admitted.resultBytes = resultLimit;
                admitted.maxStateBytes = properties.maxChildStateBytes();
                admitted.limits = new ExecutionLimits(
                        Long.MAX_VALUE,
                        millis,
                        Integer.MAX_VALUE,
                        Integer.MAX_VALUE,
                        millis,
                        state.limits.outputPreviewChars(),
                        state.limits.outputReserve());
                admitted.messages.add(
                        InferenceMessage.user(
                                encode(
                                        Map.of(
                                                "objective",
                                                admitted.objective,
                                                "references",
                                                supplied,
                                                "context",
                                                Objects.toString(admitted.context, ""),
                                                "content_trust",
                                                "untrusted_data"))));
                state.maxChildRuns = Math.min(state.maxChildRuns, properties.maxChildren());
                state.maxChildStateBytes = Math
                        .min(state.maxChildStateBytes, properties.maxAggregateStateBytes());
                state.childRuns.put(childId, admitted);
            });
            d.child = repository.get(rootId).childRuns.get(childId);
        } catch (java.util.concurrent.CancellationException cancelled) {
            throw cancelled;
        } catch (ContextSizingFailure unavailable) {
            return Optional.of(
                    failure(
                            childId,
                            "unavailable",
                            "The parent's input token count is unavailable",
                            resultLimit));
        } catch (RuntimeException rejected) {
            return Optional.of(
                    failure(
                            childId,
                            "unavailable",
                            "Child admission rejected: "
                                    + (rejected instanceof ChildRunRepository.CapacityException
                                            ? rejected.getMessage()
                                            : "invalid_references_or_capacity"),
                            resultLimit));
        }
        return Optional.empty();
    }

    /** Resumes or starts the child conversation and finishes the wrapper with its validated result. */
    private Object runChild(Delegation d) {
        String rootId = d.rootId;
        var cancellation = d.cancellation;
        var childRepository = new ChildRunRepository(repository, rootId, d.childId, d.deadline);
        var childContext = new ConversationContext(
                tools,
                childRepository,
                delegationProfiles.instructions(d.child.profile) + "\n"
                        + Objects.toString(childHooks.instructions(d.root.scope, d.child.profile), ""));
        // The child loop checks the root's charged total against the task token ceiling.
        var progress = new ExecutionProgress(
                childRepository,
                d.selectedModel::outputTokenAllowance,
                id -> true,
                taskTokenCeiling);
        try (var toolCalls = new ToolCallExecutor(childRepository, tools, authorization, progress, null);
                var runner = new ConversationRunner(
                        childRepository,
                        d.selectedModel,
                        new ContextHooks() {},
                        null,
                        childContext,
                        progress,
                        toolCalls)) {
            toolCalls.setScheduledValidator(ignored -> scheduledValidator.accept(childRepository.root()));
            toolCalls.recoverSettlements(rootId);
            d.child = childRepository.child();
            if (d.child.modelRequestInFlight
                    || d.child.requestReservations.keySet().stream().anyMatch(k -> k.startsWith("file:"))) {
                toolCalls.settleUndispatched(rootId, "interrupted_provider_request");
                childRepository.update(rootId, -1, s -> {
                    s.status = ExecutionStatus.partial;
                    s.failure = "interrupted_provider_request_usage_unknown";
                });
            } else if (!d.resume) {
                toolCalls.settleUndispatched(rootId, "automatic_resumption_disabled");
                childRepository.update(rootId, -1, s -> {
                    s.status = ExecutionStatus.partial;
                    s.failure = "automatic_resumption_disabled";
                });
            } else if (!d.child.status.terminal()) {
                runner.run(rootId, cancellation);
            }
            while (true) {
                var finished = childOutcome(d, childRepository);
                if (finished.isPresent()) {
                    return finished.get();
                }
                // A repair was requested: give the child one more conversation turn.
                runner.run(rootId, cancellation);
            }
        }
    }

    /** Finishes with the child's result, or empty after queuing one repair turn for an invalid result. */
    private Optional<Object> childOutcome(Delegation d, ChildRunRepository childRepository) {
        String rootId = d.rootId;
        var wrapper = d.wrapper;
        String childId = d.childId;
        int resultLimit = d.resultLimit;
        d.child = childRepository.child();
        d.root = repository.get(rootId);
        var root = d.root;
        var child = d.child;
        if (!current(root, wrapper)) {
            return Optional.of(
                    finishExisting(
                            rootId,
                            childId,
                            failure(
                                    childId,
                                    "superseded",
                                    "The assignment was superseded or cancelled",
                                    resultLimit)));
        }
        if (child.status != ExecutionStatus.succeeded
                && !(child.status == ExecutionStatus.partial && child.failure == null
                        && child.finalAnswer != null)) {
            return Optional.of(
                    finishExisting(
                            rootId,
                            childId,
                            failure(
                                    childId,
                                    "partial",
                                    Objects.toString(child.failure, "Child work did not complete"),
                                    resultLimit)));
        }
        try {
            Map<String, Object> result = validatedResult(d.context, child);
            String encoded = encode(result);
            if (encoded.getBytes(StandardCharsets.UTF_8).length > child.resultBytes) {
                throw new IllegalArgumentException("result_envelope_too_large");
            }
            // Check the actual escaped native result, rather than assuming byte counts add linearly.
            var messages = new ArrayList<>(d.parentMessages);
            messages.add(
                    new InferenceMessage(
                            InferenceMessage.Role.USER,
                            List.of(
                                    new InferenceContent.ToolResult(
                                            wrapper.id,
                                            List.of(new InferenceContent.Text(encoded)),
                                            false,
                                            wrapper.toolset))));
            var parentBinding = root.activeInferenceBinding();
            if (!estimate(
                    modelFor(parentBinding),
                    root,
                    messages,
                    d.parentInstructions,
                    d.parentDefinitions,
                    d.cancellation,
                    d.deadline)
                    .fits(
                            Long.MAX_VALUE,
                            parentBinding.inputAllowance(),
                            parentBinding.maxOutputTokens())) {
                throw new IllegalArgumentException("parent_context_allowance");
            }
            return Optional.of(finishExisting(rootId, childId, result));
        } catch (java.util.concurrent.CancellationException cancelled) {
            throw cancelled;
        } catch (ContextSizingFailure unavailable) {
            return Optional.of(
                    finishExisting(
                            rootId,
                            childId,
                            failure(
                                    childId,
                                    "unavailable",
                                    "The parent's input token count is unavailable",
                                    resultLimit)));
        } catch (RuntimeException invalid) {
            String artifact = repository.artifact(rootId, Objects.toString(child.finalAnswer, ""));
            if (child.repairAttempts >= 1 || !d.resume || childRepository.remainingMillis() <= 0) {
                return Optional.of(
                        finishExisting(
                                rootId,
                                childId,
                                failure(
                                        childId,
                                        "failed",
                                        "Invalid or oversized child result; details artifact " + artifact,
                                        resultLimit)));
            }
            repository.update(rootId, -1, s -> {
                var repairing = s.childRuns.get(childId);
                repairing.repairAttempts++;
                repairing.status = ExecutionStatus.running;
                repairing.finalAnswer = null;
                repairing.messages.add(
                        InferenceMessage.user(
                                SystemPrompts.CHILD_RESULT_REPAIR_INSTRUCTIONS
                                        + "\nThe complete serialized envelope, including server metadata, must fit "
                                        + repairing.resultBytes
                                        + " UTF-8 bytes. Leave 512 bytes for server metadata."));
            });
            return Optional.empty();
        }
    }

    private static ContextSizingPolicy.Estimate estimate(
            ModelClient model,
            ExecutionSnapshot snapshot,
            List<InferenceMessage> messages,
            String instructions,
            List<ToolRegistry.Definition> definitions,
            CancellationHandle cancellation,
            long deadline) {
        var request = new InferenceRequest(
                ContextSizingPolicy.completionMessages(snapshot, messages),
                instructions,
                InferenceOptions.defaults());
        var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        try {
            cancellation.check();
            if (System.nanoTime() >= deadline) {
                throw new ContextSizingFailure();
            }
            return BoundedInference.count(
                    request,
                    messages,
                    definitions,
                    () -> model.countInputTokens(request, definitions, cancellation),
                    cancellation,
                    executor,
                    () -> java.util.concurrent.TimeUnit.NANOSECONDS
                            .toMillis(Math.max(0, deadline - System.nanoTime())),
                    () -> {
                        if (System.nanoTime() >= deadline) {
                            throw new ContextSizingFailure();
                        }
                    },
                    0);
        } catch (java.util.concurrent.CancellationException cancelled) {
            throw cancelled;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException();
        } catch (Exception unavailable) {
            throw new ContextSizingFailure();
        } finally {
            executor.shutdownNow();
        }
    }

    private static final class ContextSizingFailure extends RuntimeException {}

    private ModelClient modelFor(InferenceBinding binding) {
        if (binding == null) {
            throw new IllegalStateException("Missing child inference binding");
        }
        var resolution = PinnedInference.resolve(binding, profiles);
        return switch (resolution.status()) {
            case configured -> new InferenceModelClient(resolution.profile().service());
            case unavailable -> throw new IllegalStateException("Missing child inference profile");
            case incompatible -> throw new IllegalStateException("Incompatible child inference profile");
        };
    }

    private void endWithoutDispatch(String rootId, String childId, String reason) {
        var childRepository = new ChildRunRepository(repository, rootId, childId, System.nanoTime());
        var progress = new ExecutionProgress(childRepository, model::outputTokenAllowance);
        try (var toolCalls = new ToolCallExecutor(childRepository, tools, authorization, progress, null)) {
            toolCalls.setScheduledValidator(ignored -> scheduledValidator.accept(childRepository.root()));
            toolCalls.recoverSettlements(rootId);
            toolCalls.settleUndispatched(rootId, reason);
            repository.update(rootId, -1, root -> {
                var child = root.childRuns.get(childId);
                if (!child.status.terminal()) {
                    child.status = ExecutionStatus.partial;
                }
                child.failure = reason;
                child.updatedAt = Instant.now();
                // Supersession must not erase uncertainty from an already dispatched mutation.
                if (child.blockers.contains("destination_inspection_required")
                        && !root.blockers.contains("destination_inspection_required")) {
                    root.blockers.add("destination_inspection_required");
                }
            });
        }
    }

    /** Finalize a recovered wrapper at its deadline without admitting or resuming any child work. */
    Object stopAtTimeLimit(String rootId, AcceptedCall wrapper) {
        return stopAtBoundary(
                rootId,
                wrapper,
                "delegation_timeout",
                "Child work stopped at the parent task deadline");
    }

    /** Finalize a recovered wrapper at the task token ceiling without admitting or resuming child work. */
    Object stopAtTokenCeiling(String rootId, AcceptedCall wrapper) {
        return stopAtBoundary(
                rootId,
                wrapper,
                "task_token_ceiling",
                "Child work stopped at the parent task token ceiling");
    }

    private Object stopAtBoundary(
            String rootId,
            AcceptedCall wrapper,
            String boundaryReason,
            String message) {
        var root = repository.get(rootId);
        String childId = ChildRun.id(rootId, wrapper.id, wrapper.binding, wrapper.goalGeneration);
        var child = root.childRuns.get(childId);
        int resultLimit = Math.min(properties.maxResultBytes(), root.limits.outputPreviewChars());
        boolean current = current(root, wrapper);
        if (child != null && child.result != null && current) {
            return parse(child.result);
        }
        String reason = current ? boundaryReason : "superseded";
        if (child != null) {
            endWithoutDispatch(rootId, childId, reason);
        }
        return finishExisting(
                rootId,
                childId,
                failure(
                        childId,
                        current ? "partial" : "superseded",
                        current ? message : "The assignment is no longer current",
                        resultLimit));
    }

    private Object finishExisting(String rootId, String childId, Map<String, Object> result) {
        String encoded = encode(result);
        var root = repository.update(rootId, -1, state -> {
            var child = state.childRuns.get(childId);
            if (child == null || child.result != null) {
                return;
            }
            if (!current(
                    state,
                    state.calls.stream().filter(c -> c.id.equals(child.parentCallId)).findFirst()
                            .orElseThrow())) {
                child.status = ExecutionStatus.partial;
                child.failure = "superseded";
                child.result = encode(
                        failure(
                                childId,
                                "superseded",
                                "The assignment is no longer current",
                                child.resultBytes));
            } else {
                if (!child.status.terminal()) {
                    child.status = ExecutionStatus.partial;
                }
                if ("failed".equals(result.get("status"))) {
                    child.status = ExecutionStatus.failed;
                }
                child.result = encoded;
            }
            child.updatedAt = Instant.now();
            if (child.blockers.contains("destination_inspection_required")
                    && !state.blockers.contains("destination_inspection_required")) {
                state.blockers.add("destination_inspection_required");
            }
        });
        var child = root.childRuns.get(childId);
        return child == null ? result : parse(child.result);
    }

    private boolean current(ExecutionSnapshot root, AcceptedCall wrapper) {
        return root.status == ExecutionStatus.running && !root.cancellationRequested
                && root.monitoring == null
                && root.goalGeneration == wrapper.goalGeneration
                && root.calls.stream().anyMatch(
                        c -> c.id.equals(wrapper.id) && c.state == CallState.dispatched
                                && Objects.equals(c.binding, wrapper.binding) && Objects.equals(
                                        c.binding,
                                        AuthorizationPolicy.binding(root.scope, c.id, c.name, c.input)))
                && repository.inbox(root.executionId).stream()
                        .noneMatch(c -> !root.consumedInboxIds.contains(c.id()));
    }

    private Map<String, Object> validatedResult(ToolRegistry.Context context, ChildRun child) {
        var data = parse(child.finalAnswer);
        JsonSchema.validate(RESULT_SCHEMA, data);
        if (((String) data.get("summary")).isBlank()) {
            throw new IllegalArgumentException("Missing child summary");
        }
        var refs = new ArrayList<>(referenceList(data.get("evidence")));
        for (var finding : referenceList(data.get("findings"))) {
            for (var citation : referenceList(finding.get("citations"))) {
                if (!Set.of("document", "evidence").contains(citation.get("kind"))
                        || !(citation.get("reference") instanceof String unit)
                        || !unit.matches("(?:page|image|chunk):[1-9][0-9]{0,7}")) {
                    throw new IllegalArgumentException("Invalid child citation");
                }
                refs.add(citation);
            }
        }
        references.validateChildReferences(context, refs);
        var result = new LinkedHashMap<String, Object>(data);
        // Public citations use an explicit evidence identifier; generic input references retain id.
        result.put("findings", referenceList(data.get("findings")).stream().map(finding -> {
            var normalized = new LinkedHashMap<String, Object>(finding);
            normalized.put("citations", referenceList(finding.get("citations")).stream().map(citation -> {
                var entry = new LinkedHashMap<String, Object>(citation);
                entry.put("evidence_id", entry.remove("id"));
                return entry;
            }).toList());
            return normalized;
        }).toList());
        String outcome = Objects.toString(result.remove("outcome"), "complete");
        if (child.status == ExecutionStatus.partial && "complete".equals(outcome)) {
            outcome = "partial";
        }
        if ("needs_input".equals(outcome)
                && (!(result.get("question") instanceof String question) || question.isBlank())) {
            throw new IllegalArgumentException("Missing question");
        }
        result.put("run_id", child.id);
        result.put("profile", child.profile);
        result.put("profile_version", child.profileVersion);
        result.put("status", outcome);
        result.put("content_trust", "untrusted_data");
        return result;
    }

    private static Map<String, Object> failure(String id, String status, String message, int limit) {
        var result = new LinkedHashMap<String, Object>();
        result.put("run_id", id);
        result.put("status", status);
        result.put("content_trust", "untrusted_data");
        result.put("summary", message);
        result.put("limitations", List.of("No complete child result is available"));
        // Fixed server messages and a deterministic ID fit the minimum supported 512-byte result allowance.
        if (encode(result).getBytes(StandardCharsets.UTF_8).length > Math.max(512, limit)) {
            result.put("summary", "Child work is unavailable");
        }
        return result;
    }

    static String encode(Object object) {
        try {
            return JSON.writeValueAsString(object);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid child JSON", invalid);
        }
    }

    private static Map<String, Object> parse(String raw) {
        try {
            return JSON.readValue(raw, new TypeReference<Map<String, Object>>() {});
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid child JSON", invalid);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> referenceList(Object value) {
        return (List<Map<String, Object>>) value;
    }

    private static Map<String, Object> text(int max) {
        return Map.of("type", "string", "maxLength", max);
    }

    private static Map<String, Object> array(Object items, int max) {
        return Map.of("type", "array", "items", items, "maxItems", max);
    }

    private static Map<String, Object> object(Map<String, Object> fields, String... required) {
        return Map.of(
                "type",
                "object",
                "properties",
                fields,
                "required",
                List.of(required),
                "additionalProperties",
                false);
    }

    private static final Map<String, Object> REFERENCE = object(
            Map.of(
                    "kind",
                    Map.of("type", "string", "enum", List.of("document", "evidence")),
                    "content_hash",
                    text(128),
                    "id",
                    text(128),
                    "reference",
                    text(128)),
            "kind",
            "id",
            "content_hash");
    private static final Map<String, Object> RESULT_SCHEMA = object(
            Map.of(
                    "summary",
                    text(2048),
                    "findings",
                    array(
                            object(
                                    Map.of(
                                            "kind",
                                            Map.of(
                                                    "type",
                                                    "string",
                                                    "enum",
                                                    List.of(
                                                            "source_fact",
                                                            "document_transcription",
                                                            "analysis")),
                                            "text",
                                            text(2048),
                                            "citations",
                                            array(REFERENCE, 8)),
                                    "kind",
                                    "text",
                                    "citations"),
                            12),
                    "evidence",
                    array(REFERENCE, 24),
                    "coverage",
                    array(text(512), 12),
                    "unread_inputs",
                    array(text(512), 12),
                    "limitations",
                    array(text(512), 12),
                    "question",
                    text(1024),
                    "outcome",
                    Map.of("type", "string", "enum", List.of("complete", "partial", "needs_input"))),
            "summary",
            "findings",
            "evidence",
            "coverage",
            "unread_inputs",
            "limitations");
}
