package net.velocity.agent.memory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.json.JsonMapper;

import net.velocity.agent.ToolRegistry;
import net.velocity.agent.persistence.ExecutionRepository;
import net.velocity.agent.state.ExecutionSnapshot;
import net.velocity.agent.state.TrustedScope;
import net.velocity.inference.SystemPrompts;
import net.velocity.inference.objects.InferenceContent;

/** Typed fact persistence. Every successful command and its replay receipt commit in one document. */
public final class MemoryService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final MemoryRepository repository;
    private final MemoryProperties properties;
    private final Clock clock;
    private final ExecutionRepository executions;

    /** Created only by the restricted Java reviewer; never deserialize this from model input. */
    public record ReviewPermit(
            String reviewId,
            String sourceExecutionId,
            Set<String> allowedSourceExecutionIds,
            Map<String, Long> policyGenerations,
            BooleanSupplier valid) {

        public ReviewPermit(
                String reviewId,
                String sourceExecutionId,
                Map<String, Long> policyGenerations,
                BooleanSupplier valid) {
            this(reviewId, sourceExecutionId, Set.of(sourceExecutionId), policyGenerations, valid);
        }

        public ReviewPermit {
            allowedSourceExecutionIds = Set.copyOf(allowedSourceExecutionIds);
            if (allowedSourceExecutionIds.isEmpty() || allowedSourceExecutionIds.size() > 20
                    || !allowedSourceExecutionIds.contains(sourceExecutionId)) {
                throw new IllegalArgumentException(
                        "Review evidence must identify its bounded admitted sources");
            }
            allowedSourceExecutionIds.forEach(id -> string(id, 128));
            policyGenerations = Map.copyOf(policyGenerations);
            Objects.requireNonNull(valid);
        }
    }

    private record Evidence(String executionId, String messageId, String kind, Instant sourceCreatedAt) {}

    /** Fact stores in admission order; the derived profile renders last, under the user's own facts. */
    public static final List<String> TARGETS = List.of("user", "environment", "profile");

    public MemoryService(
            MemoryRepository repository,
            MemoryProperties properties,
            Clock clock,
            ExecutionRepository executions) {
        this.repository = Objects.requireNonNull(repository);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
        this.executions = Objects.requireNonNull(executions);
    }

    /** Current entries of one store for operator views; an empty list when the feature is disabled. */
    public List<MemoryStore.Entry> entries(TrustedScope scope, String target) {
        if (!properties.enabled() || !properties.targetEnabled(target)) {
            return List.of();
        }
        return load(scope, target).entries();
    }

    public boolean reviewEnabled(TrustedScope scope) {
        return properties.enabled() && (properties.userEnabled() && load(scope, "user").enabled()
                || properties.environmentEnabled() && load(scope, "environment").enabled());
    }

    /** The derived profile is written only by background profile reviews. */
    public boolean profileReviewEnabled(TrustedScope scope) {
        return properties.targetEnabled("profile") && load(scope, "profile").enabled();
    }

    public Map<String, Object> inspect(ToolRegistry.Context context, Map<String, Object> input) {
        return known(() -> {
            fields(input, Set.of("target"));
            context.cancellation().check();
            var stores = input.containsKey("target")
                    ? List.of(load(context.scope(), target(input)))
                    : TARGETS.stream().map(target -> load(context.scope(), target)).toList();
            return Map.of(
                    "status",
                    "ok",
                    "database_committed",
                    false,
                    "content_trust",
                    "untrusted_data",
                    "stores",
                    stores.stream().map(this::view).toList());
        });
    }

    /** Called exactly at root admission; never read mutable facts during ordinary model turns. */
    public String snapshot(TrustedScope scope) {
        if (!properties.enabled()) {
            return "";
        }
        var result = new StringBuilder();
        for (String target : TARGETS) {
            if (!properties.targetEnabled(target)) {
                continue;
            }
            MemoryStore store = load(scope, target);
            if (!store.enabled()) {
                continue;
            }
            if (used(store.entries()) > properties.maxChars(target)) {
                throw new IllegalStateException(
                        "Saved facts exceed the configured capacity; consolidate them through operator controls");
            }
            if (target.equals("profile")) {
                if (store.entries().isEmpty()) {
                    continue;
                }
                // Inference, never user statements: framed so the model weighs it accordingly.
                result.append("<derived_profile revision=\"").append(store.revision())
                        .append("\" used_chars=\"").append(used(store.entries()))
                        .append("\" max_chars=\"").append(properties.maxChars(target)).append("\">\n")
                        .append(SystemPrompts.DERIVED_PROFILE_NOTICE).append('\n')
                        .append(render(store.entries())).append("</derived_profile>\n");
                continue;
            }
            result.append("<saved_facts target=\"").append(target).append("\" revision=\"")
                    .append(store.revision()).append("\" used_chars=\"").append(used(store.entries()))
                    .append("\" max_chars=\"").append(properties.maxChars(target)).append("\">\n")
                    .append(render(store.entries())).append("</saved_facts>\n");
        }
        return result.toString();
    }

    public Map<String, Long> policyGenerations(TrustedScope scope) {
        return Map.of(
                "user",
                load(scope, "user").policyGeneration(),
                "environment",
                load(scope, "environment").policyGeneration(),
                "profile",
                load(scope, "profile").policyGeneration());
    }

    public Map<String, Object> update(ToolRegistry.Context context, Map<String, Object> input) {
        return known(() -> mutate(context, input, null, false));
    }

    public Map<String, Object> operatorUpdate(ToolRegistry.Context context, Map<String, Object> input) {
        return known(() -> {
            requireOperator(context);
            return mutate(context, input, null, true);
        });
    }

    public Map<String, Object> reviewUpdate(
            ToolRegistry.Context context,
            ReviewPermit permit,
            Map<String, Object> input) {
        return known(() -> {
            if (!permit.reviewId().equals(context.executionId()) || !permit.valid().getAsBoolean()) {
                return result("forbidden", null, false, List.of());
            }
            return mutate(context, input, permit, false);
        });
    }

    /** Read-only recovery of an exact accepted call. An absent receipt never authorizes replay. */
    public Optional<Object> reconcile(ToolRegistry.Context context, Map<String, Object> input) {
        return reconcile(context, input, "update");
    }

    public Optional<Object> reconcile(
            ToolRegistry.Context context,
            Map<String, Object> input,
            String command) {
        if (!Set.of("update", "control", "proposal").contains(command)) {
            throw new IllegalArgumentException("Invalid memory command");
        }
        String target = target(input);
        String identity = identity(context, input, false);
        MemoryStore store = load(context.scope(), target);
        var receipt = store.receipts().stream().filter(item -> item.identity().equals(identity)).findFirst();
        if (receipt.isEmpty()) {
            return Optional.empty();
        }
        if (!receipt.get().digest().equals(commandDigest(context, input, command))) {
            throw new IllegalArgumentException("Mutation identity has different input");
        }
        return Optional.of(receipt.get().result());
    }

    /** Review recovery retains only exact Java-produced call binding and target, never rejected model text. */
    public Optional<Object> reconcileReceipt(ToolRegistry.Context context, String target) {
        target(Map.of("target", target));
        String identity = identity(context, Map.of(), false);
        var receipt = load(context.scope(), target).receipts().stream()
                .filter(item -> item.identity().equals(identity)).findFirst();
        if (receipt.isEmpty()) {
            return Optional.empty();
        }
        if (!receipt.get().bindingDigest().equals(digest(context.acceptedCallBinding()))) {
            throw new IllegalArgumentException("Mutation binding does not match the committed receipt");
        }
        return Optional.of(receipt.get().result());
    }

    private Map<String, Object> mutate(
            ToolRegistry.Context context,
            Map<String, Object> input,
            ReviewPermit review,
            boolean operator) {
        fields(
                input,
                operator
                        ? Set.of("request_id", "target", "expected_revision", "operations")
                        : Set.of(
                                "target",
                                "expected_revision",
                                "operations",
                                "source_message_id",
                                "source_execution_id"));
        String target = target(input), identity = identity(context, input, operator);
        String digest = commandDigest(context, input, "update");
        MemoryStore current = load(context.scope(), target);
        Map<String, Object> replay = replay(current, identity, digest);
        if (replay != null) {
            return replay;
        }
        if (!enabled(current)) {
            return result("disabled", current, false, List.of());
        }
        boolean profile = target.equals("profile");
        if (profile && !operator && review == null) {
            // The derived profile is review-written inference; a task never edits it directly.
            return result("forbidden", current, false, List.of());
        }
        long expected = integer(input.get("expected_revision"));
        if (expected != current.revision()) {
            return result("revision_conflict", current, false, List.of());
        }
        if (atWriteLimit(current, context, operator, review != null)) {
            return result("write_limit", current, false, List.of());
        }
        var operations = operations(input.get("operations"));
        Evidence evidence = operator
                ? new Evidence(
                        "local-operator",
                        string(input.get("request_id"), 128),
                        "explicit_user",
                        clock.instant())
                : profile
                        ? new Evidence(review.sourceExecutionId(), "derived", "derived", clock.instant())
                        : evidence(context, review, input);
        if (review != null && !reviewAllowed(current, review, evidence)) {
            return result("forbidden", current, false, List.of());
        }
        var entries = new ArrayList<>(current.entries());
        var changed = new ArrayList<String>();
        apply(entries, operations, evidence, changed);
        if (used(entries) > properties.maxChars(target)) {
            return result("capacity_exceeded", current, false, List.of());
        }
        boolean destructive = operations.stream().anyMatch(operation -> !operation.action().equals("add"));
        // A profile rewrite replaces derived text, never a user's statement, so it applies directly.
        if (review != null && destructive && !profile) {
            if (current.proposals().size() >= properties.maxProposals()) {
                return result("capacity_exceeded", current, false, List.of());
            }
            var proposals = new ArrayList<>(current.proposals());
            String proposalId = UUID.randomUUID().toString();
            proposals.add(
                    new MemoryStore.Proposal(
                            proposalId,
                            current.revision() + 1,
                            current.policyGeneration(),
                            operations,
                            evidence.executionId(),
                            evidence.messageId(),
                            review.reviewId(),
                            clock.instant()));
            MemoryStore next = copy(
                    current,
                    current.revision() + 1,
                    current.enabled(),
                    current.policyGeneration(),
                    current.suppressedThrough(),
                    current.entries(),
                    current.receipts(),
                    proposals);
            var response = result("staged", next, false, List.of());
            response.put("proposal_id", proposalId);
            response.put("proposal_committed", true);
            return commit(context, current, next, identity, digest, response, review);
        }
        boolean altered = !changed.isEmpty();
        MemoryStore next = copy(
                current,
                current.revision() + 1,
                current.enabled(),
                current.policyGeneration() + (destructive && altered ? 1 : 0),
                destructive && altered ? clock.instant() : current.suppressedThrough(),
                entries,
                current.receipts(),
                current.proposals());
        return commit(
                context,
                current,
                next,
                identity,
                digest,
                result(altered ? "saved" : "unchanged", next, true, changed),
                review);
    }

    public Map<String, Object> control(ToolRegistry.Context context, Map<String, Object> input) {
        return known(() -> {
            requireOperator(context);
            return controlCommand(context, input, true);
        });
    }

    /** Authenticated foreground adapter command; never accepts authority inferred from model arguments. */
    public Map<String, Object> foregroundControl(ToolRegistry.Context context, Map<String, Object> input) {
        return known(() -> {
            String target = target(input);
            if (!(input.get("enabled") instanceof Boolean enabled)) {
                throw new IllegalArgumentException("enabled must be boolean");
            }
            if (!exactRequest(context, "memory " + (enabled ? "enable" : "disable") + " " + target)) {
                return result("forbidden", null, false, List.of());
            }
            return controlCommand(context, input, false);
        });
    }

    private Map<String, Object> controlCommand(
            ToolRegistry.Context context,
            Map<String, Object> input,
            boolean operator) {
        fields(
                input,
                operator
                        ? Set.of("request_id", "target", "expected_revision", "enabled")
                        : Set.of("target", "expected_revision", "enabled"));
        String identity = identity(context, input, operator),
                digest = commandDigest(context, input, "control");
        MemoryStore current = load(context.scope(), target(input));
        var replay = replay(current, identity, digest);
        if (replay != null) {
            return replay;
        }
        if (integer(input.get("expected_revision")) != current.revision()) {
            return result("revision_conflict", current, false, List.of());
        }
        if (!(input.get("enabled") instanceof Boolean enabled)) {
            throw new IllegalArgumentException("enabled must be boolean");
        }
        MemoryStore next = copy(
                current,
                current.revision() + 1,
                enabled,
                current.policyGeneration() + 1,
                clock.instant(),
                current.entries(),
                current.receipts(),
                List.of());
        return commit(context, current, next, identity, digest, result("saved", next, true, List.of()), null);
    }

    public Map<String, Object> proposalDecision(ToolRegistry.Context context, Map<String, Object> input) {
        return known(() -> {
            requireOperator(context);
            return proposalCommand(context, input, true);
        });
    }

    public Map<String, Object> foregroundProposal(ToolRegistry.Context context, Map<String, Object> input) {
        return known(() -> {
            String target = target(input), proposalId = string(input.get("proposal_id"), 64);
            if (!(input.get("apply") instanceof Boolean apply)) {
                throw new IllegalArgumentException("apply must be boolean");
            }
            if (!exactRequest(
                    context,
                    "memory proposal " + (apply ? "apply" : "reject") + " " + target + " " + proposalId)) {
                return result("forbidden", null, false, List.of());
            }
            return proposalCommand(context, input, false);
        });
    }

    private Map<String, Object> proposalCommand(
            ToolRegistry.Context context,
            Map<String, Object> input,
            boolean operator) {
        fields(
                input,
                operator
                        ? Set.of("request_id", "target", "proposal_id", "apply")
                        : Set.of("target", "proposal_id", "apply"));
        String identity = identity(context, input, operator),
                digest = commandDigest(context, input, "proposal");
        MemoryStore current = load(context.scope(), target(input));
        var replay = replay(current, identity, digest);
        if (replay != null) {
            return replay;
        }
        String proposalId = string(input.get("proposal_id"), 64);
        var proposal = current.proposals().stream().filter(item -> item.id().equals(proposalId)).findFirst()
                .orElse(null);
        if (proposal == null) {
            return result("not_found", current, false, List.of());
        }
        if (!(input.get("apply") instanceof Boolean apply)) {
            throw new IllegalArgumentException("apply must be boolean");
        }
        var entries = new ArrayList<>(current.entries());
        var changed = new ArrayList<String>();
        if (apply) {
            if (!enabled(current)) {
                return result("disabled", current, false, List.of());
            }
            if (proposal.expectedRevision() != current.revision()
                    || proposal.policyGeneration() != current.policyGeneration()) {
                return result("revision_conflict", current, false, List.of());
            }
            // Read the authoritative owner again: a deleted source invalidates a staged proposal too.
            ExecutionSnapshot source = executions.get(proposal.sourceExecutionId());
            if (!context.scope().sameOwner(source.scope)) {
                return result("forbidden", current, false, List.of());
            }
            apply(
                    entries,
                    proposal.operations(),
                    new Evidence(
                            proposal.sourceExecutionId(),
                            proposal.sourceMessageId(),
                            "explicit_user",
                            source.createdAt),
                    changed);
            if (used(entries) > properties.maxChars(current.target())) {
                return result("capacity_exceeded", current, false, List.of());
            }
        }
        var proposals = current.proposals().stream().filter(item -> !item.id().equals(proposalId)).toList();
        MemoryStore next = copy(
                current,
                current.revision() + 1,
                current.enabled(),
                current.policyGeneration() + 1,
                clock.instant(),
                entries,
                current.receipts(),
                proposals);
        return commit(
                context,
                current,
                next,
                identity,
                digest,
                result(apply ? "saved" : "rejected", next, true, changed),
                null);
    }

    private boolean exactRequest(ToolRegistry.Context context, String command) {
        if (context.childRunId() != null || context.acceptedCallId().isBlank()
                || context.acceptedCallBinding().isBlank()
                || !Set.of("local", "http", "slack").contains(context.scope().origin())) {
            return false;
        }
        ExecutionSnapshot source;
        try {
            source = executions.get(context.executionId());
        } catch (net.velocity.agent.persistence.NotFoundException absent) {
            return false;
        }
        if (!context.scope().sameOwner(source.scope) || source.monitoring != null
                || source.cancellationRequested
                || source.purpose != net.velocity.agent.state.TaskPurpose.generic) {
            return false;
        }
        String latest = source.corrections.isEmpty()
                ? source.originalRequest
                : source.corrections.getLast().text();
        return latest != null && latest.strip().equals(command);
    }

    private void apply(
            List<MemoryStore.Entry> entries,
            List<MemoryStore.Operation> operations,
            Evidence evidence,
            List<String> changed) {
        for (var operation : operations) {
            if (operation.action().equals("add")) {
                if (entries.stream().anyMatch(entry -> entry.text().equals(operation.content()))) {
                    continue;
                }
                String id = UUID.randomUUID().toString();
                entries.add(
                        new MemoryStore.Entry(
                                id,
                                operation.content(),
                                evidence.kind(),
                                evidence.executionId(),
                                evidence.messageId(),
                                clock.instant(),
                                clock.instant()));
                changed.add(id);
            } else {
                int index = -1;
                for (int i = 0; i < entries.size(); i++) {
                    if (entries.get(i).id().equals(operation.entryId())) {
                        index = i;
                        break;
                    }
                }
                if (index < 0) {
                    throw new IllegalArgumentException("Entry not found; inspect current entries");
                }
                var old = entries.get(index);
                if (operation.action().equals("remove")) {
                    entries.remove(index);
                    changed.add(old.id());
                } else if (!old.text().equals(operation.content())) {
                    if (entries.stream().anyMatch(
                            entry -> !entry.id().equals(old.id())
                                    && entry.text().equals(operation.content()))) {
                        throw new IllegalArgumentException("Replacement duplicates another entry");
                    }
                    entries.set(
                            index,
                            new MemoryStore.Entry(
                                    old.id(),
                                    operation.content(),
                                    evidence.kind(),
                                    evidence.executionId(),
                                    evidence.messageId(),
                                    old.createdAt(),
                                    clock.instant()));
                    changed.add(old.id());
                }
            }
        }
    }

    private Evidence evidence(ToolRegistry.Context context, ReviewPermit review, Map<String, Object> input) {
        String defaultSource = review == null ? context.executionId() : review.sourceExecutionId();
        String sourceId = string(input.getOrDefault("source_execution_id", defaultSource), 128);
        String messageId = string(input.getOrDefault("source_message_id", "request"), 256);
        if (review == null
                ? !sourceId.equals(context.executionId())
                : !review.allowedSourceExecutionIds().contains(sourceId)) {
            throw new IllegalArgumentException(
                    "Evidence execution is outside the trusted admitted source set");
        }
        ExecutionSnapshot source = executions.get(sourceId);
        if (!context.scope().sameOwner(source.scope)) {
            throw new IllegalArgumentException("Evidence owner does not match");
        }
        boolean user = messageId.equals("request") && source.originalRequest != null
                && !source.originalRequest.isBlank()
                || source.corrections.stream().anyMatch(item -> item.id().equals(messageId))
                || source.sourceMessages.stream().anyMatch(
                        item -> item.source().messageId().equals(messageId)
                                && item.authorId().equals(context.scope().requesterId())
                                && item.botId() == null);
        if (!user) {
            user = source.messageMetadata.stream().filter(
                    item -> item.messageId().equals(messageId)
                            && item.role().equals("USER") && item.sequence() >= 0
                            && item.sequence() < source.messages.size())
                    .anyMatch(
                            item -> source.messages.get(item.sequence()).content().stream().anyMatch(
                                    content -> content instanceof InferenceContent.Text text && (text.text()
                                            .equals(source.originalRequest)
                                            || source.corrections.stream().anyMatch(
                                                    correction -> text.text().equals(correction.text()))
                                            || source.sourceMessages.stream().anyMatch(
                                                    message -> message.authorId()
                                                            .equals(context.scope().requesterId())
                                                            && message.botId() == null && message.toMessage()
                                                                    .content().contains(content)))));
        }
        if (!user) {
            throw new IllegalArgumentException(
                    "Evidence must reference an original user statement owned by this account");
        }
        String resolvedMessageId = messageId;
        if (messageId.equals("request")) {
            resolvedMessageId = source.messageMetadata.stream()
                    .filter(
                            item -> item.role().equals("USER") && item.sequence() >= 0
                                    && item.sequence() < source.messages.size())
                    .filter(
                            item -> source.messages.get(item.sequence()).content().stream().anyMatch(
                                    content -> content instanceof InferenceContent.Text text
                                            && text.text().equals(source.originalRequest)))
                    .map(item -> item.messageId()).findFirst().orElse("request");
        }
        return new Evidence(sourceId, resolvedMessageId, "explicit_user", source.createdAt);
    }

    private boolean reviewAllowed(MemoryStore store, ReviewPermit permit, Evidence evidence) {
        return permit.valid().getAsBoolean()
                && Objects.equals(permit.policyGenerations().get(store.target()), store.policyGeneration())
                && (store.suppressedThrough() == null || evidence.sourceCreatedAt() != null
                        && evidence.sourceCreatedAt().isAfter(store.suppressedThrough()));
    }

    private Map<String, Object> commit(
            ToolRegistry.Context context,
            MemoryStore current,
            MemoryStore next,
            String identity,
            String digest,
            Map<String, Object> response,
            ReviewPermit review) {
        if (current.receipts().size() >= properties.maxReceipts()) {
            return result("receipt_capacity", current, false, List.of());
        }
        var receipts = new ArrayList<>(current.receipts());
        receipts.add(
                new MemoryStore.Receipt(
                        identity,
                        digest,
                        digest(context.acceptedCallBinding()),
                        context.executionId(),
                        response));
        next = copy(
                next,
                next.revision(),
                next.enabled(),
                next.policyGeneration(),
                next.suppressedThrough(),
                next.entries(),
                receipts,
                next.proposals());
        if (MemoryCodec.size(next) > 8_000_000) {
            return result("receipt_capacity", current, false, List.of());
        }
        context.cancellation().check();
        if (review != null && !review.valid().getAsBoolean()) {
            return result("forbidden", current, false, List.of());
        }
        if (repository.compareAndSet(next, current.revision())) {
            return response;
        }
        MemoryStore latest = load(context.scope(), current.target());
        var replay = replay(latest, identity, digest);
        return replay == null ? result("revision_conflict", latest, false, List.of()) : replay;
    }

    private boolean atWriteLimit(
            MemoryStore current,
            ToolRegistry.Context context,
            boolean operator,
            boolean reviewing) {
        if (operator) {
            return false;
        }
        if (!reviewing) {
            ExecutionSnapshot execution = executions.get(context.executionId());
            if (execution != null && execution.usesTaskDeadlineOnly()) {
                return false;
            }
        }
        return current.receipts().stream()
                .filter(item -> item.executionId().equals(context.executionId()))
                .count() >= properties.maxWritesPerExecution();
    }

    private static Map<String, Object> replay(MemoryStore current, String identity, String digest) {
        var receipt = current.receipts().stream().filter(item -> item.identity().equals(identity)).findFirst()
                .orElse(null);
        if (receipt == null) {
            return null;
        }
        if (!receipt.digest().equals(digest)) {
            throw new IllegalArgumentException("Mutation identity has different input");
        }
        return receipt.result();
    }

    private MemoryStore load(TrustedScope scope, String target) {
        String owner = scope.requireOwnerAccountId();
        MemoryStore store = repository.find(owner, target).orElseGet(
                () -> new MemoryStore(owner, target, 1, 0, true, 0, null, List.of(), List.of(), List.of()));
        validateStore(store);
        if (!store.ownerAccountId().equals(owner) || !store.target().equals(target)) {
            throw new IllegalStateException("Memory identity mismatch");
        }
        return store;
    }

    private boolean enabled(MemoryStore store) {
        return properties.targetEnabled(store.target()) && store.enabled();
    }

    private Map<String, Object> view(MemoryStore store) {
        var result = new LinkedHashMap<String, Object>();
        result.put("target", store.target());
        result.put("revision", store.revision());
        result.put("enabled", store.enabled());
        result.put("feature_enabled", properties.targetEnabled(store.target()));
        result.put("policy_generation", store.policyGeneration());
        result.put(
                "entries",
                store.entries().stream().map(entry -> MemoryCodec.json(MemoryCodec.entry(entry))).toList());
        result.put(
                "proposals",
                store.proposals().stream().map(proposal -> MemoryCodec.json(MemoryCodec.proposal(proposal)))
                        .toList());
        result.put("used_chars", used(store.entries()));
        result.put("max_chars", properties.maxChars(store.target()));
        result.put(
                "remaining_chars",
                Math.max(0, properties.maxChars(store.target()) - used(store.entries())));
        result.put("counting", "utf16_with_entry_delimiters");
        return result;
    }

    private LinkedHashMap<String, Object> result(
            String status,
            MemoryStore store,
            boolean committed,
            List<String> changed) {
        var result = new LinkedHashMap<String, Object>();
        result.put("status", status);
        result.put("database_committed", committed);
        result.put("changed_ids", List.copyOf(changed));
        if (store != null) {
            result.put("store", view(store));
            result.put("revision", store.revision());
            result.put("used_chars", used(store.entries()));
            result.put("max_chars", properties.maxChars(store.target()));
        }
        return result;
    }

    /** Capacity counts the exact rendered entries, including IDs, prefix, separator and newline, as UTF-16 code units. */
    public static String render(List<MemoryStore.Entry> entries) {
        var result = new StringBuilder();
        entries.forEach(
                entry -> result.append("- [").append(entry.id()).append("] ")
                        .append(entry.text()).append('\n'));
        return result.toString();
    }

    public static int used(List<MemoryStore.Entry> entries) {
        return render(entries).length();
    }

    static void validateStore(MemoryStore store) {
        try {
            if (store.formatVersion() != 1 || store.ownerAccountId() == null
                    || !store.ownerAccountId()
                            .matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
                    || !TARGETS.contains(store.target()) || store.revision() < 0
                    || store.policyGeneration() < 0) {
                throw new IllegalArgumentException("Invalid memory structure");
            }
            Set<String> ids = new HashSet<>(), texts = new HashSet<>(), receipts = new HashSet<>(),
                    proposals = new HashSet<>();
            for (var entry : store.entries()) {
                UUID.fromString(entry.id());
                MemoryText.validate(entry.text(), Integer.MAX_VALUE);
                string(entry.sourceExecutionId(), 128);
                string(entry.sourceMessageId(), 256);
                // Only the profile store may hold derived (model-inferred) text.
                boolean evidenceKind = entry.evidenceKind().equals("explicit_user")
                        || entry.evidenceKind().equals("derived") && store.target().equals("profile");
                if (!ids.add(entry.id()) || !texts.add(entry.text()) || !evidenceKind
                        || entry.createdAt() == null || entry.updatedAt() == null
                        || entry.updatedAt().isBefore(entry.createdAt())) {
                    throw new IllegalArgumentException("Invalid memory entry");
                }
            }
            for (var receipt : store.receipts()) {
                if (!receipt.identity().matches("[a-f0-9]{64}") || !receipt.digest().matches("[a-f0-9]{64}")
                        || !receipt.bindingDigest().matches("[a-f0-9]{64}")
                        || !receipts.add(receipt.identity())) {
                    throw new IllegalArgumentException("Invalid memory receipt");
                }
                string(receipt.executionId(), 128);
                validateReceiptText(receipt.result());
                if (!Set.of("saved", "unchanged", "staged", "rejected")
                        .contains(receipt.result().get("status"))
                        || !(receipt.result().get("database_committed") instanceof Boolean)
                        || !(receipt.result().get("revision") instanceof Number revision)
                        || revision.longValue() < 1 || revision.longValue() > store.revision()) {
                    throw new IllegalArgumentException("Invalid semantic receipt");
                }
            }
            for (var proposal : store.proposals()) {
                if (!proposals.add(string(proposal.id(), 64)) || proposal.expectedRevision() < 1
                        || proposal.policyGeneration() < 0 || proposal.createdAt() == null) {
                    throw new IllegalArgumentException("Invalid proposal");
                }
                string(proposal.sourceExecutionId(), 128);
                string(proposal.sourceMessageId(), 256);
                string(proposal.reviewId(), 128);
                validateOperations(proposal.operations());
            }
        } catch (RuntimeException malformed) {
            throw new IllegalStateException("Invalid persisted memory store");
        }
    }

    private static void validateReceiptText(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (var item : map.entrySet()) {
                if ((item.getKey().equals("text") || item.getKey().equals("content"))
                        && item.getValue() instanceof String text) {
                    MemoryText.validate(text, Integer.MAX_VALUE);
                } else {
                    validateReceiptText(item.getValue());
                }
            }
        } else if (value instanceof List<?> list) {
            list.forEach(MemoryService::validateReceiptText);
        }
    }

    private static List<MemoryStore.Operation> operations(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty() || list.size() > 20) {
            throw new IllegalArgumentException("Use 1 to 20 operations");
        }
        List<MemoryStore.Operation> operations = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> operation)
                    || !Set.of("action", "entry_id", "content").containsAll(operation.keySet())) {
                throw new IllegalArgumentException("Invalid operation fields");
            }
            operations.add(
                    new MemoryStore.Operation(
                            string(operation.get("action"), 16),
                            operation.containsKey("entry_id") ? string(operation.get("entry_id"), 64) : null,
                            operation.containsKey("content")
                                    ? string(operation.get("content"), Integer.MAX_VALUE)
                                    : null));
        }
        validateOperations(operations);
        return List.copyOf(operations);
    }

    private static void validateOperations(List<MemoryStore.Operation> operations) {
        if (operations.isEmpty() || operations.size() > 20) {
            throw new IllegalArgumentException("Use 1 to 20 operations");
        }
        for (var operation : operations) {
            switch (operation.action()) {
                case "add" -> {
                    if (operation.entryId() != null) {
                        throw new IllegalArgumentException("Add cannot select an existing entry");
                    }
                    MemoryText.validate(operation.content(), Integer.MAX_VALUE);
                }
                case "replace" -> {
                    string(operation.entryId(), 64);
                    MemoryText.validate(operation.content(), Integer.MAX_VALUE);
                }
                case "remove" -> {
                    string(operation.entryId(), 64);
                    if (operation.content() != null) {
                        throw new IllegalArgumentException("Remove accepts only an entry ID");
                    }
                }
                default -> throw new IllegalArgumentException("Unknown memory action");
            }
        }
    }

    private static MemoryStore copy(
            MemoryStore store,
            long revision,
            boolean enabled,
            long generation,
            Instant suppression,
            List<MemoryStore.Entry> entries,
            List<MemoryStore.Receipt> receipts,
            List<MemoryStore.Proposal> proposals) {
        return new MemoryStore(
                store.ownerAccountId(),
                store.target(),
                1,
                revision,
                enabled,
                generation,
                suppression,
                entries,
                receipts,
                proposals);
    }

    private static String target(Map<String, Object> input) {
        String target = string(input.get("target"), 16);
        if (!TARGETS.contains(target)) {
            throw new IllegalArgumentException("Unknown memory target");
        }
        return target;
    }

    private static void requireOperator(ToolRegistry.Context context) {
        context.scope().requireOwnerAccountId();
        if (!context.executionId().equals("local-operator") || !context.acceptedCallId().isEmpty()
                || !context.acceptedCallBinding().isEmpty()
                || !context.scope().origin().equals("local")
                || !context.scope().channelId().equals("operator")
                || !context.scope().botId().equals("velocity")) {
            throw new IllegalArgumentException("Trusted operator action required");
        }
    }

    private static String identity(
            ToolRegistry.Context context,
            Map<String, Object> input,
            boolean operator) {
        String localId;
        if (operator) {
            requireOperator(context);
            localId = "operator:" + string(input.get("request_id"), 128);
        } else {
            if (context.acceptedCallId().isBlank() || context.acceptedCallBinding().isBlank()) {
                throw new IllegalArgumentException("Accepted tool call identity is required");
            }
            localId = "tool:" + string(context.executionId(), 128) + ":"
                    + Objects.toString(context.childRunId(), "")
                    + ":" + context.acceptedCallId();
        }
        return digest(List.of(context.scope().requireOwnerAccountId(), localId));
    }

    private static String commandDigest(
            ToolRegistry.Context context,
            Map<String, Object> input,
            String command) {
        return digest(Map.of("command", command, "input", input, "binding", context.acceptedCallBinding()));
    }

    private static String digest(Object value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(canonical(value))));
        } catch (Exception failure) {
            throw new IllegalArgumentException("Cannot encode memory command");
        }
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            var result = new TreeMap<String, Object>();
            map.forEach((key, child) -> result.put((String) key, canonical(child)));
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(MemoryService::canonical).toList();
        }
        return value;
    }

    private static String string(Object value, int max) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > max) {
            throw new IllegalArgumentException("Invalid bounded string field");
        }
        return text;
    }

    private static long integer(Object value) {
        if (!(value instanceof Integer || value instanceof Long) || ((Number) value).longValue() < 0
                || ((Number) value).longValue() == Long.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid expected revision");
        }
        return ((Number) value).longValue();
    }

    private static void fields(Map<String, Object> input, Set<String> allowed) {
        if (input == null || !allowed.containsAll(input.keySet())) {
            throw new IllegalArgumentException("Unknown memory request fields");
        }
    }

    private static Map<String, Object> known(Supplier<Map<String, Object>> action) {
        try {
            return action.get();
        } catch (IllegalArgumentException invalid) {
            return Map.of(
                    "status",
                    "invalid_request",
                    "database_committed",
                    false,
                    "message",
                    Objects.toString(invalid.getMessage(), "Invalid memory request"));
        }
    }
}
