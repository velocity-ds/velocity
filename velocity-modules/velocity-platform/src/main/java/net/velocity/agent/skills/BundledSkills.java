package net.velocity.agent.skills;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import net.velocity.agent.ToolRegistry;

/** Original, inert Velocity workflows, installed explicitly through the ordinary account-owned skill store. */
public final class BundledSkills {

    private final List<BundledSkill> catalog;
    private final SkillService skills;

    /**
     * The catalog is every {@link BundledSkillSource}'s bundles in order. Fails when two bundles share an id, so a
     * misconfigured feature fails startup instead of shadowing another bundle.
     */
    public BundledSkills(SkillService skills, List<BundledSkillSource> sources) {
        this.skills = Objects.requireNonNull(skills);
        var all = new ArrayList<BundledSkill>();
        var ids = new HashSet<String>();
        for (BundledSkillSource source : Objects.requireNonNull(sources)) {
            for (BundledSkill bundle : Objects.requireNonNull(source).bundles()) {
                if (!ids.add(Objects.requireNonNull(bundle).id())) {
                    throw new IllegalArgumentException("Duplicate bundled skill id: " + bundle.id());
                }
                all.add(bundle);
            }
        }
        this.catalog = List.copyOf(all);
    }

    /** Bundle ids in catalog order. */
    public List<String> ids() {
        return catalog.stream().map(BundledSkill::id).toList();
    }

    public Map<String, Object> list(ToolRegistry.Context context) {
        requireOperator(context);
        return Map.of(
                "status",
                "ok",
                "feature_enabled",
                skills.enabled(),
                "bundles",
                catalog.stream().map(BundledSkill::preview).toList());
    }

    /** Only the trusted local adapter can select from this fixed catalog; no model tool installs bundles. */
    public Map<String, Object> install(ToolRegistry.Context context, String bundleId) {
        requireOperator(context);
        String requestId = context.acceptedCallId();
        if (!UUID.fromString(requestId).toString().equals(requestId)
                || !context.acceptedCallBinding().equals("operator:" + requestId)) {
            throw new IllegalArgumentException("Bound operator request required");
        }
        BundledSkill bundle = catalog.stream().filter(value -> value.id().equals(bundleId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown skill bundle"));
        String identity = "bundle:" + bundle.id() + ":" + bundle.version();
        var install = ToolRegistry.Context.builder()
                .executionId(context.executionId())
                .scope(context.scope())
                .cancellation(context.cancellation())
                .progressReporter(context.progressReporter())
                .usageReporter(context.usageReporter())
                .acceptedCall(identity, "operator:" + identity)
                .providerRequestAdmission(context.providerRequestAdmission())
                .operationDeadlineNanos(context.operationDeadlineNanos())
                .build();
        // One durable create receipt per account/bundle/version survives user edits, renames and disablement.
        // A replay returns the original receipt; clients reload the current skill before editing or enabling it.
        return skills.create(install, bundle.input());
    }

    private static void requireOperator(ToolRegistry.Context context) {
        context.cancellation().check();
        context.scope().requireOwnerAccountId();
        if (!context.executionId().equals("local-operator") || !context.scope().origin().equals("local")
                || !context.scope().channelId().equals("operator")
                || !context.scope().botId().equals("velocity")
                || context.childRunId() != null) {
            throw new IllegalArgumentException("Trusted operator adapter required");
        }
    }
}
