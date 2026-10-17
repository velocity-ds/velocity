package net.velocity.agent.skills;

import java.util.List;
import java.util.Map;

/** One owner aggregate makes content, immutable versions and replay receipts a single CAS. */
public record SkillStore(
        int formatVersion,
        String ownerAccountId,
        long revision,
        long policyGeneration,
        List<Skill> skills,
        List<Receipt> receipts,
        List<Proposal> proposals) {

    /**
     * The single readable store format. Format 2 added per-skill usage telemetry and curator proposals; a
     * format 1 document is upgraded on read with empty usage and rewritten in format 2 by its next change.
     */
    public static final int FORMAT_VERSION = 2;

    public SkillStore {
        skills = List.copyOf(skills);
        receipts = List.copyOf(receipts);
        proposals = proposals == null ? List.of() : List.copyOf(proposals);
    }

    public static SkillStore empty(String owner) {
        return new SkillStore(FORMAT_VERSION, owner, 0, 0, List.of(), List.of(), List.of());
    }

    /** A curator's staged retirement of one skill; only the operator applies or rejects it. */
    public record Proposal(
            String id,
            String skillId,
            String action,
            String reason,
            String reviewId,
            long expectedRevision,
            String createdAt) {}

    public record Skill(
            String id,
            String name,
            String routingDescription,
            String coreInstructions,
            List<Reference> references,
            long revision,
            String authorKind,
            boolean managedByAgent,
            boolean pinned,
            boolean enabled,
            String sourceExecutionId,
            String createdAt,
            String updatedAt,
            List<Version> versions,
            Usage usage) {

        public Skill {
            references = List.copyOf(references);
            versions = List.copyOf(versions);
            usage = usage == null ? Usage.none() : usage;
        }

        public Skill withUsage(Usage replacement) {
            return new Skill(
                    id,
                    name,
                    routingDescription,
                    coreInstructions,
                    references,
                    revision,
                    authorKind,
                    managedByAgent,
                    pinned,
                    enabled,
                    sourceExecutionId,
                    createdAt,
                    updatedAt,
                    versions,
                    replacement);
        }
    }

    /**
     * Best-effort usage telemetry for curation and the learning journey. Loads count only from tasks,
     * never from reviews or the console; patches count from any author. A load after the latest patch
     * marks the patched skill as reused, the only cheap signal that an improvement was picked up.
     */
    public record Usage(
            long loadCount,
            long patchCount,
            String lastLoadedAt,
            String lastPatchedAt,
            boolean reusedAfterPatch) {

        public static Usage none() {
            return new Usage(0, 0, null, null, false);
        }

        public Usage loaded(String now) {
            return new Usage(loadCount + 1, patchCount, now, lastPatchedAt, lastPatchedAt != null);
        }

        public Usage patched(String now) {
            return new Usage(loadCount, patchCount + 1, lastLoadedAt, now, false);
        }
    }

    public record Reference(String id, String name, String content, long revision) {}

    public record Version(
            long revision,
            String name,
            String routingDescription,
            String coreInstructions,
            List<Reference> references,
            String authorKind,
            boolean managedByAgent,
            boolean pinned,
            boolean enabled,
            String sourceExecutionId,
            String updatedAt) {

        public Version {
            references = List.copyOf(references);
        }
    }

    public record Receipt(String identity, String digest, Map<String, Object> result) {

        public Receipt {
            result = immutable(result);
        }

        private static Map<String, Object> immutable(Map<String, Object> input) {
            var out = new java.util.LinkedHashMap<String, Object>();
            input.forEach((key, value) -> out.put(key, freeze(value)));
            return Map.copyOf(out);
        }

        @SuppressWarnings("unchecked")
        private static Object freeze(Object value) {
            if (value instanceof Map<?, ?> map) {
                return immutable((Map<String, Object>) map);
            }
            if (value instanceof List<?> list) {
                return list.stream().map(Receipt::freeze).toList();
            }
            return value;
        }
    }
}
