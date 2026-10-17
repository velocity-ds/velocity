package net.velocity.agent.skills;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import net.velocity.agent.CancellationHandle;
import net.velocity.agent.ToolRegistry;
import net.velocity.agent.skills.SkillStore.*;
import net.velocity.agent.state.TrustedScope;
import net.velocity.agent.text.StoredTextSafety;

/** Account-owned inert procedural knowledge. Reading content never grants execution authority. */
public final class SkillService {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).build();
    private static final Pattern UNSAFE = Pattern.compile(
            "(?is)(?:ignore|override|bypass|disregard)\\s+(?:all\\s+|the\\s+)?(?:previous|system|security|authorization|safety)\\b");
    private final SkillRepository repository;
    private final SkillsProperties properties;
    private final Clock clock;

    public SkillService(SkillRepository repository, SkillsProperties properties, Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
    }

    public boolean enabled() {
        return properties.enabled();
    }

    public SkillsProperties limits() {
        return properties;
    }

    public long policyGeneration(TrustedScope scope) {
        return read(scope).policyGeneration();
    }

    public String admissionIndex(TrustedScope scope) {
        if (!properties.enabled()) {
            return "";
        }
        String index = renderIndex(read(scope));
        if (index.length() > properties.maximumIndexChars()) {
            throw new IllegalStateException("Skill index exceeds configured capacity");
        }
        return index;
    }

    public Map<String, Object> list(ToolRegistry.Context context, Map<String, Object> input) {
        context.cancellation().check();
        String search = input.containsKey("search")
                ? text(input.get("search"), 100).toLowerCase(Locale.ROOT)
                : "";
        var matched = read(context.scope()).skills().stream().filter(
                skill -> search.isEmpty()
                        || (skill.name() + " " + skill.routingDescription()).toLowerCase(Locale.ROOT)
                                .contains(search))
                .map(this::describe).toList();
        int offset = input.containsKey("offset") ? Math.toIntExact(number(input, "offset", 0)) : 0;
        int limit = input.containsKey("limit")
                ? Math.toIntExact(number(input, "limit", 1))
                : properties.maximumSkills();
        if (limit > properties.maximumSkills()) {
            throw new IllegalArgumentException("Skill page exceeds configured capacity");
        }
        var skills = new ArrayList<Map<String, Object>>();
        for (int i = offset; i < matched.size() && skills.size() < limit; i++) {
            skills.add(matched.get(i));
        }
        boolean more = offset + skills.size() < matched.size();
        var result = new LinkedHashMap<String, Object>();
        result.put("status", "ok");
        result.put("skills", List.copyOf(skills));
        result.put("feature_enabled", properties.enabled());
        result.put("content_trust", "untrusted_data");
        result.put("has_more", more);
        result.put("next_offset", offset + skills.size());
        result.put(
                "proposals",
                read(context.scope()).proposals().stream().map(SkillService::proposalView).toList());
        return result;
    }

    public Map<String, Object> load(ToolRegistry.Context context, Map<String, Object> input) {
        // Console reads through the local operator adapter are inspection, not use.
        return load(context, input, !context.executionId().equals("local-operator"));
    }

    private Map<String, Object> load(ToolRegistry.Context context, Map<String, Object> input, boolean count) {
        context.cancellation().check();
        SkillStore store = read(context.scope());
        Skill skill = find(store, id(input, "id"));
        if (skill == null) {
            return result("not_found");
        }
        long revision = input.containsKey("revision") ? number(input, "revision", 1) : skill.revision();
        Version version = version(skill, revision);
        if (version == null) {
            return result("version_not_found");
        }
        if (count) {
            skill = recordLoad(store, skill);
        }
        return loadResult(skill, version);
    }

    /** Best-effort usage telemetry: a lost race or unavailable store changes nothing and never fails the read. */
    private synchronized Skill recordLoad(SkillStore store, Skill skill) {
        if (!properties.enabled()) {
            return skill;
        }
        Skill counted = skill.withUsage(skill.usage().loaded(clock.instant().toString()));
        var skills = new ArrayList<>(store.skills());
        skills.replaceAll(entry -> entry.id().equals(skill.id()) ? counted : entry);
        SkillStore next = new SkillStore(
                SkillStore.FORMAT_VERSION,
                store.ownerAccountId(),
                store.revision() + 1,
                store.policyGeneration(),
                skills,
                store.receipts(),
                store.proposals());
        try {
            return repository.compareAndSet(next, store.revision()) ? counted : skill;
        } catch (RuntimeException unavailable) {
            return skill;
        }
    }

    private Map<String, Object> loadResult(Skill skill, Version version) {
        var out = new LinkedHashMap<String, Object>();
        out.put("status", "found");
        out.put("skill", describe(skill));
        out.put("loaded_revision", version.revision());
        out.put("core_instructions", version.coreInstructions());
        out.put(
                "references",
                version.references().stream().map(
                        reference -> Map.of(
                                "id",
                                reference.id(),
                                "name",
                                reference.name(),
                                "revision",
                                reference.revision(),
                                "chars",
                                reference.content().length()))
                        .toList());
        out.put(
                "versions",
                skill.versions().stream()
                        .map(
                                v -> Map.of(
                                        "revision",
                                        v.revision(),
                                        "updated_at",
                                        v.updatedAt(),
                                        "source_execution_id",
                                        v.sourceExecutionId(),
                                        "author_kind",
                                        v.authorKind()))
                        .toList());
        out.put("content_trust", "untrusted_data");
        out.put("complete", true);
        return out;
    }

    public Map<String, Object> reference(ToolRegistry.Context context, Map<String, Object> input) {
        context.cancellation().check();
        Skill skill = find(read(context.scope()), id(input, "id"));
        if (skill == null) {
            return result("not_found");
        }
        Version version = version(skill, number(input, "revision", 1));
        if (version == null) {
            return result("version_not_found");
        }
        Reference ref = version.references().stream().filter(r -> r.id().equals(id(input, "reference_id")))
                .findFirst().orElse(null);
        if (ref == null) {
            return result("not_found");
        }
        return Map.of(
                "status",
                "found",
                "reference_id",
                ref.id(),
                "revision",
                ref.revision(),
                "skill_revision",
                version.revision(),
                "content",
                ref.content(),
                "complete",
                true,
                "content_trust",
                "untrusted_data");
    }

    public Map<String, Object> create(ToolRegistry.Context context, Map<String, Object> input) {
        return mutate(context, input, "create", null);
    }

    public Map<String, Object> patch(ToolRegistry.Context context, Map<String, Object> input) {
        return mutate(context, input, "patch", null);
    }

    /** Only trusted operator adapters call controls; controls are absent from model schemas. */
    public Map<String, Object> control(ToolRegistry.Context context, Map<String, Object> input) {
        context.scope().requireOwnerAccountId();
        if (!context.executionId().equals("local-operator") || !context.scope().origin().equals("local")
                || !context.scope().channelId().equals("operator")
                || !context.scope().botId().equals("velocity")) {
            throw new IllegalArgumentException("Trusted operator control required");
        }
        return mutate(context, input, "control", null);
    }

    public Optional<Object> reconcile(
            ToolRegistry.Context context,
            Map<String, Object> input,
            String action) {
        String identity = identity(context), digest = digest(Map.of("action", action, "input", input));
        return read(context.scope()).receipts().stream()
                .filter(receipt -> receipt.identity().equals(identity)).findFirst().map(receipt -> {
                    if (!receipt.digest().equals(digest)) {
                        throw new IllegalArgumentException(
                                "Mutation identity was reused with different input");
                    }
                    return receipt.result();
                });
    }

    /** Review recovery already pins the exact trusted accepted-call binding; no rejected input is retained. */
    public Optional<Object> reconcileReceipt(ToolRegistry.Context context) {
        String identity = identity(context);
        return read(context.scope()).receipts().stream()
                .filter(receipt -> receipt.identity().equals(identity))
                .findFirst().map(Receipt::result);
    }

    public static final class ReviewSession {

        private final SkillService service;
        private final String owner, reviewId;
        private final long generation;
        private final CancellationHandle cancellation;
        private final BooleanSupplier currentPolicy;
        /** A user-requested review creates user-owned, protected skills instead of agent-managed ones. */
        private final boolean userRequested;
        private final Map<String, Long> readGrants = new HashMap<>();
        private final Set<String> referenceGrants = new HashSet<>();
        private boolean discovered;

        private ReviewSession(
                SkillService service,
                String owner,
                String reviewId,
                long generation,
                CancellationHandle cancellation,
                BooleanSupplier currentPolicy,
                boolean userRequested) {
            this.service = service;
            this.owner = owner;
            this.reviewId = reviewId;
            this.generation = generation;
            this.cancellation = cancellation;
            this.currentPolicy = currentPolicy;
            this.userRequested = userRequested;
        }
    }

    public ReviewSession beginReview(
            TrustedScope scope,
            String reviewId,
            CancellationHandle cancellation,
            BooleanSupplier currentPolicy,
            boolean userRequested) {
        text(reviewId, 128);
        return new ReviewSession(
                this,
                scope.requireOwnerAccountId(),
                reviewId,
                read(scope).policyGeneration(),
                cancellation,
                currentPolicy,
                userRequested);
    }

    public Map<String, Object> reviewList(
            ReviewSession review,
            ToolRegistry.Context context,
            Map<String, Object> input) {
        checkReview(review, context, read(context.scope()));
        review.discovered = true;
        return list(context, input);
    }

    public Map<String, Object> reviewLoad(
            ReviewSession review,
            ToolRegistry.Context context,
            Map<String, Object> input) {
        SkillStore store = read(context.scope());
        checkReview(review, context, store);
        // Review reads never count as use; only tasks that follow a skill do.
        var result = load(context, input, false);
        if ("found".equals(result.get("status"))) {
            Skill skill = find(store, id(input, "id"));
            if (((Number) result.get("loaded_revision")).longValue() == skill.revision()) {
                review.readGrants.put(skill.id(), skill.revision());
            }
        }
        return Map.copyOf(result);
    }

    public Map<String, Object> reviewReference(
            ReviewSession review,
            ToolRegistry.Context context,
            Map<String, Object> input) {
        checkReview(review, context, read(context.scope()));
        var result = reference(context, input);
        if ("found".equals(result.get("status"))) {
            review.referenceGrants.add(
                    id(input, "id") + ":" + number(input, "revision", 1) + ":" + id(input, "reference_id"));
        }
        return Map.copyOf(result);
    }

    public Map<String, Object> reviewCreate(
            ReviewSession review,
            ToolRegistry.Context context,
            Map<String, Object> input) {
        return mutate(context, input, "create", review);
    }

    /** A curator stages the retirement of one agent-managed skill it has freshly read; the user decides. */
    public synchronized Map<String, Object> reviewCurate(
            ReviewSession review,
            ToolRegistry.Context context,
            Map<String, Object> input) {
        context.cancellation().check();
        var replay = reconcile(context, input, "curate");
        if (replay.isPresent()) {
            @SuppressWarnings("unchecked")
            var receipt = (Map<String, Object>) replay.get();
            return receipt;
        }
        if (!properties.enabled()) {
            return result("disabled");
        }
        SkillStore current = read(context.scope());
        checkReview(review, context, current);
        Skill skill = find(current, id(input, "skill_id"));
        if (skill == null) {
            return result("not_found");
        }
        String reason = text(input.get("reason"), 300);
        if (!skill.authorKind().equals("agent") || !skill.managedByAgent() || skill.pinned()) {
            return result("protected");
        }
        if (!skill.enabled()) {
            return result("disabled");
        }
        if (!Objects.equals(review.readGrants.get(skill.id()), skill.revision())) {
            return result("fresh_read_required");
        }
        if (current.proposals().stream().anyMatch(proposal -> proposal.skillId().equals(skill.id()))) {
            return result("already_proposed");
        }
        if (current.proposals().size() >= 20 || current.receipts().size() >= 1024) {
            return failure("capacity_exceeded", "Proposal or receipt capacity reached.");
        }
        var proposals = new ArrayList<>(current.proposals());
        proposals.add(
                new Proposal(
                        UUID.randomUUID().toString(),
                        skill.id(),
                        "disable",
                        reason,
                        review.reviewId,
                        skill.revision(),
                        clock.instant().toString()));
        Map<String, Object> receipt = Map.of(
                "status",
                "staged",
                "database_committed",
                true,
                "proposal_committed",
                true,
                "skill",
                metadata(skill));
        var receipts = new ArrayList<>(current.receipts());
        receipts.add(
                new Receipt(identity(context), digest(Map.of("action", "curate", "input", input)), receipt));
        SkillStore next = new SkillStore(
                SkillStore.FORMAT_VERSION,
                current.ownerAccountId(),
                current.revision() + 1,
                current.policyGeneration(),
                current.skills(),
                receipts,
                proposals);
        validateStore(next);
        context.cancellation().check();
        checkReview(review, context, current);
        if (!repository.compareAndSet(next, current.revision())) {
            var committed = reconcile(context, input, "curate");
            if (committed.isPresent()) {
                @SuppressWarnings("unchecked")
                var saved = (Map<String, Object>) committed.get();
                return saved;
            }
            return result("revision_conflict");
        }
        return receipt;
    }

    /** Operator decision on a curator proposal: apply disables the skill, reject just drops the proposal. */
    public Map<String, Object> proposalDecision(ToolRegistry.Context context, Map<String, Object> input) {
        context.scope().requireOwnerAccountId();
        if (!context.executionId().equals("local-operator") || !context.scope().origin().equals("local")
                || !context.scope().channelId().equals("operator")
                || !context.scope().botId().equals("velocity")) {
            throw new IllegalArgumentException("Trusted operator control required");
        }
        String proposalId = id(input, "proposal_id");
        boolean apply = bool(input, "apply");
        SkillStore current = read(context.scope());
        Proposal proposal = current.proposals().stream().filter(item -> item.id().equals(proposalId))
                .findFirst().orElse(null);
        if (proposal == null) {
            return result("not_found");
        }
        Skill skill = find(current, proposal.skillId());
        if (apply) {
            if (skill == null) {
                return result("not_found");
            }
            return mutate(
                    context,
                    Map.of(
                            "id",
                            skill.id(),
                            "expected_revision",
                            skill.revision(),
                            "enabled",
                            false,
                            "proposal_id",
                            proposalId),
                    "control",
                    null);
        }
        return rejectProposal(context, current, proposalId);
    }

    private synchronized Map<String, Object> rejectProposal(
            ToolRegistry.Context context,
            SkillStore current,
            String proposalId) {
        SkillStore next = new SkillStore(
                SkillStore.FORMAT_VERSION,
                current.ownerAccountId(),
                current.revision() + 1,
                current.policyGeneration(),
                current.skills(),
                current.receipts(),
                current.proposals().stream().filter(item -> !item.id().equals(proposalId)).toList());
        context.cancellation().check();
        return repository.compareAndSet(next, current.revision())
                ? Map.of("status", "rejected", "database_committed", true)
                : result("revision_conflict");
    }

    /** The curator's input: every skill with provenance, usage telemetry and any pending proposal. */
    public String curationIndex(TrustedScope scope) {
        SkillStore store = read(scope);
        var text = new StringBuilder();
        for (Skill skill : store.skills().stream().sorted(Comparator.comparing(Skill::name)).toList()) {
            Usage usage = skill.usage();
            boolean proposed = store.proposals().stream()
                    .anyMatch(proposal -> proposal.skillId().equals(skill.id()));
            text.append("- id=").append(skill.id()).append(" | ").append(skill.name())
                    .append(" | author=").append(skill.authorKind())
                    .append(" managed=").append(skill.managedByAgent())
                    .append(" pinned=").append(skill.pinned())
                    .append(" enabled=").append(skill.enabled())
                    .append(" revision=").append(skill.revision())
                    .append(" created=").append(skill.createdAt())
                    .append(" | loads=").append(usage.loadCount())
                    .append(" last_loaded=").append(Objects.requireNonNullElse(usage.lastLoadedAt(), "never"))
                    .append(" patches=").append(usage.patchCount())
                    .append(" last_patched=")
                    .append(Objects.requireNonNullElse(usage.lastPatchedAt(), "never"))
                    .append(" reused_after_patch=").append(usage.reusedAfterPatch())
                    .append(proposed ? " | retirement already proposed" : "")
                    .append(" | routing: ").append(skill.routingDescription()).append('\n');
        }
        return text.isEmpty() ? "(no skills)\n" : text.toString();
    }

    private static Map<String, Object> proposalView(Proposal proposal) {
        var view = new LinkedHashMap<String, Object>();
        view.put("id", proposal.id());
        view.put("skill_id", proposal.skillId());
        view.put("action", proposal.action());
        view.put("reason", proposal.reason());
        view.put("review_id", proposal.reviewId());
        view.put("expected_revision", proposal.expectedRevision());
        view.put("created_at", proposal.createdAt());
        return view;
    }

    public Map<String, Object> reviewPatch(
            ReviewSession review,
            ToolRegistry.Context context,
            Map<String, Object> input) {
        return mutate(context, input, "patch", review);
    }

    private synchronized Map<String, Object> mutate(
            ToolRegistry.Context context,
            Map<String, Object> input,
            String action,
            ReviewSession review) {
        context.cancellation().check();
        var replay = reconcile(context, input, action);
        if (replay.isPresent()) {
            @SuppressWarnings("unchecked")
            var receipt = (Map<String, Object>) replay.get();
            return receipt;
        }
        if (!properties.enabled()) {
            return result("disabled");
        }
        SkillStore current = read(context.scope());
        if (review != null) {
            checkReview(review, context, current);
        }
        if (current.receipts().size() >= 1024) {
            return failure(
                    "capacity_exceeded",
                    "Receipt capacity reached; retained receipts cannot be silently discarded.");
        }
        Skill previous = action.equals("create") ? null : find(current, id(input, "id"));
        if (!action.equals("create") && previous == null) {
            return result("not_found");
        }
        if (previous != null && previous.revision() != number(input, "expected_revision", 1)) {
            return Map.of(
                    "status",
                    "revision_conflict",
                    "database_committed",
                    false,
                    "current_revision",
                    previous.revision());
        }
        if (previous != null && !previous.enabled() && !action.equals("control")) {
            return result("disabled");
        }
        if (review != null) {
            if (previous == null && !review.discovered) {
                return failure("invalid_request", "Discover relevant skills before creating one.");
            }
            if (previous != null && (!previous.authorKind().equals("agent") || !previous.managedByAgent()
                    || previous.pinned())) {
                return result("protected");
            }
            if (previous != null
                    && !Objects.equals(review.readGrants.get(previous.id()), previous.revision())) {
                return result("fresh_read_required");
            }
            if (previous != null && input.containsKey("references")
                    && previous.references().stream().anyMatch(
                            reference -> !review.referenceGrants.contains(
                                    previous.id() + ":" + previous.revision() + ":" + reference.id()))) {
                return result("fresh_read_required");
            }
        }
        if (previous == null && current.skills().size() >= properties.maximumSkills()) {
            return failure("capacity_exceeded", "Skill count is at capacity; improve an existing skill.");
        }
        String now = clock.instant().toString();
        String skillId = previous == null ? UUID.randomUUID().toString() : previous.id();
        String name = value(input, "name", previous == null ? null : previous.name(), 100);
        String routing = value(
                input,
                "routing_description",
                previous == null ? null : previous.routingDescription(),
                300);
        String core = value(
                input,
                "core_instructions",
                previous == null ? null : previous.coreInstructions(),
                properties.maximumCoreChars());
        if (current.skills().stream()
                .anyMatch(skill -> !skill.id().equals(skillId) && skill.name().equalsIgnoreCase(name))) {
            return failure("revision_conflict", "A skill with this name exists; load and update it.");
        }
        List<Reference> references = input.containsKey("references")
                ? references(input.get("references"), previous)
                : previous == null ? List.of() : previous.references();
        boolean enabled = previous == null || previous.enabled(),
                pinned = previous != null && previous.pinned();
        boolean agentAuthored = review != null && !review.userRequested;
        boolean managed = previous == null ? agentAuthored : previous.managedByAgent();
        String author = previous == null ? agentAuthored ? "agent" : "user" : previous.authorKind();
        if (action.equals("control")) {
            if (input.containsKey("enabled")) {
                enabled = bool(input, "enabled");
            }
            if (input.containsKey("pinned")) {
                pinned = bool(input, "pinned");
            }
            if (input.containsKey("managed_by_agent")) {
                managed = bool(input, "managed_by_agent");
                if (managed) {
                    author = "agent";
                }
            }
        }
        long revision = previous == null ? 1 : previous.revision() + 1;
        var versions = new ArrayList<Version>(previous == null ? List.of() : previous.versions());
        versions.add(
                new Version(
                        revision,
                        name,
                        routing,
                        core,
                        references,
                        author,
                        managed,
                        pinned,
                        enabled,
                        context.executionId(),
                        now));
        while (versions.size() > properties.retainedVersions()) {
            versions.removeFirst();
        }
        Skill replacement = new Skill(
                skillId,
                name,
                routing,
                core,
                references,
                revision,
                author,
                managed,
                pinned,
                enabled,
                context.executionId(),
                previous == null ? now : previous.createdAt(),
                now,
                versions,
                previous == null
                        ? Usage.none()
                        : action.equals("patch") ? previous.usage().patched(now) : previous.usage());
        var skills = new ArrayList<>(current.skills());
        skills.removeIf(skill -> skill.id().equals(skillId));
        skills.add(replacement);
        Map<String, Object> receipt = Map
                .of("status", "saved", "database_committed", true, "skill", metadata(replacement));
        var receipts = new ArrayList<>(current.receipts());
        receipts.add(
                new Receipt(identity(context), digest(Map.of("action", action, "input", input)), receipt));
        // Applying a curator proposal disables the skill and retires the proposal in the same commit.
        List<Proposal> proposals = action.equals("control")
                && input.get("proposal_id") instanceof String decided
                        ? current.proposals().stream().filter(proposal -> !proposal.id().equals(decided))
                                .toList()
                        : current.proposals();
        SkillStore next = new SkillStore(
                SkillStore.FORMAT_VERSION,
                current.ownerAccountId(),
                current.revision() + 1,
                current.policyGeneration() + (action.equals("control") ? 1 : 0),
                skills,
                receipts,
                proposals);
        if (renderIndex(next).length() > properties.maximumIndexChars()) {
            return failure(
                    "capacity_exceeded",
                    "Skill index capacity exceeded; shorten routing descriptions.");
        }
        validateStore(next);
        context.cancellation().check();
        if (review != null) {
            checkReview(review, context, current);
        }
        if (!repository.compareAndSet(next, current.revision())) {
            var committed = reconcile(context, input, action);
            if (committed.isPresent()) {
                @SuppressWarnings("unchecked")
                var saved = (Map<String, Object>) committed.get();
                return saved;
            }
            return result("revision_conflict");
        }
        if (review != null) {
            review.readGrants.remove(skillId);
        }
        return receipt;
    }

    private void checkReview(ReviewSession review, ToolRegistry.Context context, SkillStore store) {
        review.cancellation.check();
        context.cancellation().check();
        if (review.service != this || !review.owner.equals(context.scope().requireOwnerAccountId())
                || !properties.enabled()
                || review.generation != store.policyGeneration() || !review.currentPolicy.getAsBoolean()) {
            throw new IllegalStateException("Skill review authority is stale");
        }
    }

    private SkillStore read(TrustedScope scope) {
        String owner = scope.requireOwnerAccountId();
        SkillStore store = repository.find(owner).orElseGet(() -> SkillStore.empty(owner));
        validateStore(store);
        if (!store.ownerAccountId().equals(owner)) {
            throw new IllegalStateException("Skill owner mismatch");
        }
        return store;
    }

    /** Metadata plus usage telemetry for list, load and console views; receipts keep the fixed metadata. */
    private Map<String, Object> describe(Skill skill) {
        var result = metadata(skill);
        result.put("load_count", skill.usage().loadCount());
        result.put("patch_count", skill.usage().patchCount());
        result.put("last_loaded_at", skill.usage().lastLoadedAt());
        result.put("last_patched_at", skill.usage().lastPatchedAt());
        result.put("reused_after_patch", skill.usage().reusedAfterPatch());
        return result;
    }

    /** Skill creation and update events for the learning journey, newest first. */
    public List<Map<String, Object>> timeline(TrustedScope scope) {
        var events = new ArrayList<Map<String, Object>>();
        for (Skill skill : read(scope).skills()) {
            if (skill.versions().getFirst().revision() > 1) {
                // Older versions were pruned by the retention limit; keep the creation visible.
                events.add(
                        event(
                                skill,
                                "skill_created",
                                skill.createdAt(),
                                1,
                                skill.name(),
                                skill.authorKind(),
                                null));
            }
            for (Version version : skill.versions()) {
                events.add(
                        event(
                                skill,
                                version.revision() == 1 ? "skill_created" : "skill_updated",
                                version.updatedAt(),
                                version.revision(),
                                version.name(),
                                version.authorKind(),
                                version.sourceExecutionId()));
            }
        }
        events.sort(Comparator.comparing((Map<String, Object> event) -> (String) event.get("at")).reversed());
        return List.copyOf(events);
    }

    private static Map<String, Object> event(
            Skill skill,
            String kind,
            String at,
            long revision,
            String name,
            String author,
            String sourceExecutionId) {
        var event = new LinkedHashMap<String, Object>();
        event.put("kind", kind);
        event.put("at", at);
        event.put("skill_id", skill.id());
        event.put("name", name);
        event.put("revision", revision);
        event.put("author_kind", author);
        event.put("source_execution_id", sourceExecutionId);
        event.put("enabled", skill.enabled());
        event.put("load_count", skill.usage().loadCount());
        event.put("reused_after_patch", skill.usage().reusedAfterPatch());
        return event;
    }

    private Map<String, Object> metadata(Skill skill) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", skill.id());
        result.put("name", skill.name());
        result.put("routing_description", skill.routingDescription());
        result.put("revision", skill.revision());
        result.put("author_kind", skill.authorKind());
        result.put("managed_by_agent", skill.managedByAgent());
        result.put("pinned", skill.pinned());
        result.put("enabled", skill.enabled());
        result.put("created_at", skill.createdAt());
        result.put("updated_at", skill.updatedAt());
        result.put("source_execution_id", skill.sourceExecutionId());
        result.put(
                "automatic_edit_allowed",
                skill.enabled() && skill.authorKind().equals("agent") && skill.managedByAgent()
                        && !skill.pinned());
        return result;
    }

    private static String renderIndex(SkillStore store) {
        return store.skills().stream().filter(Skill::enabled).sorted(Comparator.comparing(Skill::name))
                .map(
                        skill -> skill.id() + " | " + skill.name() + " | revision " + skill.revision() + " | "
                                + skill.routingDescription() + "\n")
                .collect(java.util.stream.Collectors.joining());
    }

    private List<Reference> references(Object value, Skill previous) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("References must be an array");
        }
        var out = new ArrayList<Reference>();
        var ids = new HashSet<String>();
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> map) || !map.keySet().equals(Set.of("id", "name", "content"))) {
                throw new IllegalArgumentException("Invalid reference fields");
            }
            String id = text(map.get("id"), 64);
            if (!id.matches("[A-Za-z0-9_-]+") || !ids.add(id)) {
                throw new IllegalArgumentException("Invalid or duplicate reference ID");
            }
            String name = text(map.get("name"), 100),
                    content = text(map.get("content"), properties.maximumReferenceChars());
            long revision = previous == null
                    ? 1
                    : previous.references().stream().filter(r -> r.id().equals(id))
                            .mapToLong(r -> r.revision() + 1).findFirst().orElse(1);
            out.add(new Reference(id, name, content, revision));
        }
        return List.copyOf(out);
    }

    public static void validateStore(SkillStore store) {
        if (store == null || store.formatVersion() != SkillStore.FORMAT_VERSION
                || store.ownerAccountId() == null
                || !store.ownerAccountId()
                        .matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
                || store.revision() < 0 || store.policyGeneration() < 0
                || store.policyGeneration() > store.revision()
                || store.receipts().size() > 1024) {
            throw new IllegalArgumentException("Invalid skill store");
        }
        var ids = new HashSet<String>();
        for (Skill skill : store.skills()) {
            if (!ids.add(skill.id()) || !skill.id().matches("[A-Za-z0-9_-]{1,64}") || skill.revision() < 1
                    || !Set.of("user", "agent", "bundled", "installed", "external")
                            .contains(skill.authorKind())
                    || skill.versions().isEmpty()) {
                throw new IllegalArgumentException("Invalid skill record");
            }
            text(skill.name(), 100);
            text(skill.routingDescription(), 300);
            text(skill.coreInstructions(), Integer.MAX_VALUE);
            text(skill.sourceExecutionId(), 128);
            Instant.parse(skill.createdAt());
            Instant.parse(skill.updatedAt());
            validateReferences(skill.references());
            Usage usage = skill.usage();
            if (usage.loadCount() < 0 || usage.patchCount() < 0
                    || usage.reusedAfterPatch() && usage.lastPatchedAt() == null) {
                throw new IllegalArgumentException("Invalid skill usage");
            }
            if (usage.lastLoadedAt() != null) {
                Instant.parse(usage.lastLoadedAt());
            }
            if (usage.lastPatchedAt() != null) {
                Instant.parse(usage.lastPatchedAt());
            }
            long prior = 0;
            for (Version version : skill.versions()) {
                if (version.revision() <= prior || version.revision() > skill.revision()) {
                    throw new IllegalArgumentException("Invalid skill version order");
                }
                prior = version.revision();
                text(version.name(), 100);
                text(version.routingDescription(), 300);
                text(version.coreInstructions(), Integer.MAX_VALUE);
                text(version.sourceExecutionId(), 128);
                if (!Set.of("user", "agent", "bundled", "installed", "external")
                        .contains(version.authorKind())) {
                    throw new IllegalArgumentException("Invalid version author");
                }
                validateReferences(version.references());
                Instant.parse(version.updatedAt());
            }
            Version last = skill.versions().getLast();
            if (last.revision() != skill.revision()
                    || !last.coreInstructions().equals(skill.coreInstructions())
                    || !last.name().equals(skill.name())
                    || !last.routingDescription().equals(skill.routingDescription())
                    || !last.references().equals(skill.references())
                    || !last.authorKind().equals(skill.authorKind())
                    || last.enabled() != skill.enabled() || last.pinned() != skill.pinned()
                    || last.managedByAgent() != skill.managedByAgent()) {
                throw new IllegalArgumentException("Skill version does not match current content");
            }
        }
        var receipts = new HashSet<String>();
        for (Receipt receipt : store.receipts()) {
            var keys = receipt.result().keySet();
            if (!receipts.add(receipt.identity()) || !receipt.identity().matches("[a-f0-9]{64}")
                    || !receipt.digest().matches("[a-f0-9]{64}")
                    || !keys.containsAll(Set.of("status", "database_committed", "skill"))
                    || !Set.of("status", "database_committed", "skill", "proposal_committed")
                            .containsAll(keys)
                    || !Boolean.TRUE.equals(receipt.result().get("database_committed"))
                    || !Set.of("saved", "staged").contains(receipt.result().get("status"))) {
                throw new IllegalArgumentException("Invalid skill receipt");
            }
            validateMetadata(receipt.result().get("skill"));
        }
        var proposals = new HashSet<String>();
        if (store.proposals().size() > 20) {
            throw new IllegalArgumentException("Too many skill proposals");
        }
        for (Proposal proposal : store.proposals()) {
            if (!proposals.add(proposal.id()) || !proposal.id().matches("[A-Za-z0-9_-]{1,64}")
                    || !"disable".equals(proposal.action()) || proposal.expectedRevision() < 1
                    || !ids.contains(proposal.skillId())) {
                throw new IllegalArgumentException("Invalid skill proposal");
            }
            text(proposal.reason(), 300);
            text(proposal.reviewId(), 128);
            Instant.parse(proposal.createdAt());
        }
    }

    @SuppressWarnings("unchecked")
    private static void validateMetadata(Object value) {
        if (!(value instanceof Map<?, ?> raw) || !raw.keySet().equals(
                Set.of(
                        "id",
                        "name",
                        "routing_description",
                        "revision",
                        "author_kind",
                        "managed_by_agent",
                        "pinned",
                        "enabled",
                        "created_at",
                        "updated_at",
                        "source_execution_id",
                        "automatic_edit_allowed"))) {
            throw new IllegalArgumentException("Invalid skill receipt metadata");
        }
        Map<String, Object> metadata = (Map<String, Object>) raw;
        id(metadata, "id");
        text(metadata.get("name"), 100);
        text(metadata.get("routing_description"), 300);
        number(metadata, "revision", 1);
        text(metadata.get("source_execution_id"), 128);
        if (!Set.of("user", "agent", "bundled", "installed", "external")
                .contains(metadata.get("author_kind"))) {
            throw new IllegalArgumentException("Invalid receipt author");
        }
        for (String key : List.of("managed_by_agent", "pinned", "enabled", "automatic_edit_allowed")) {
            bool(metadata, key);
        }
        Instant.parse(text(metadata.get("created_at"), 64));
        Instant.parse(text(metadata.get("updated_at"), 64));
    }

    private static void validateReferences(List<Reference> references) {
        Set<String> ids = new HashSet<>();
        for (Reference ref : references) {
            if (!ref.id().matches("[A-Za-z0-9_-]{1,64}") || !ids.add(ref.id()) || ref.revision() < 1) {
                throw new IllegalArgumentException("Invalid skill reference");
            }
            text(ref.name(), 100);
            text(ref.content(), Integer.MAX_VALUE);
        }
    }

    private static Skill find(SkillStore store, String id) {
        return store.skills().stream().filter(skill -> skill.id().equals(id)).findFirst().orElse(null);
    }

    private static Version version(Skill skill, long revision) {
        return skill.versions().stream().filter(v -> v.revision() == revision).findFirst().orElse(null);
    }

    private static String id(Map<String, Object> input, String key) {
        String id = text(input.get(key), 64);
        if (!id.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid logical ID");
        }
        return id;
    }

    private static String value(Map<String, Object> input, String key, String fallback, int max) {
        return text(input.getOrDefault(key, fallback), max);
    }

    private static String text(Object value, int max) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > max
                || UNSAFE.matcher(text).find() || StoredTextSafety.containsRecognizableSecret(text)
                || StoredTextSafety.unicodeIssue(text, true) != StoredTextSafety.UnicodeIssue.NONE) {
            throw new IllegalArgumentException(
                    "Text is invalid, oversized, or contains protected information/instructions");
        }
        return text;
    }

    private static long number(Map<String, Object> input, String key, long minimum) {
        Object value = input.get(key);
        if (!(value instanceof Number n) || n.doubleValue() != n.longValue() || n.longValue() < minimum
                || n.longValue() >= Long.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid revision");
        }
        return n.longValue();
    }

    private static boolean bool(Map<String, Object> input, String key) {
        if (!(input.get(key) instanceof Boolean value)) {
            throw new IllegalArgumentException("Invalid boolean control");
        }
        return value;
    }

    private static String identity(ToolRegistry.Context context) {
        if (context.acceptedCallId().isBlank() || context.acceptedCallBinding().isBlank()) {
            throw new IllegalArgumentException("Bound mutation identity required");
        }
        return digest(
                List.of(
                        context.scope().requireOwnerAccountId(),
                        context.executionId(),
                        context.acceptedCallId(),
                        context.acceptedCallBinding(),
                        Objects.toString(context.childRunId(), "")));
    }

    private static String digest(Object object) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(object)));
        } catch (Exception impossible) {
            throw new IllegalStateException("Unable to fingerprint skill operation", impossible);
        }
    }

    private static Map<String, Object> result(String status) {
        return Map.of("status", status, "database_committed", false);
    }

    private static Map<String, Object> failure(String status, String message) {
        return Map.of("status", status, "database_committed", false, "message", message);
    }
}
