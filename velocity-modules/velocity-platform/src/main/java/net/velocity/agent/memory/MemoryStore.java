package net.velocity.agent.memory;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** One immutable bounded aggregate; facts, proposals and mutation receipts share the atomic CAS. */
public record MemoryStore(
        String ownerAccountId,
        String target,
        int formatVersion,
        long revision,
        boolean enabled,
        long policyGeneration,
        Instant suppressedThrough,
        List<Entry> entries,
        List<Receipt> receipts,
        List<Proposal> proposals) {

    public MemoryStore {
        entries = List.copyOf(entries);
        receipts = List.copyOf(receipts);
        proposals = List.copyOf(proposals);
    }

    public record Entry(
            String id,
            String text,
            String evidenceKind,
            String sourceExecutionId,
            String sourceMessageId,
            Instant createdAt,
            Instant updatedAt) {}

    public record Operation(String action, String entryId, String content) {}

    public record Receipt(
            String identity,
            String digest,
            String bindingDigest,
            String executionId,
            Map<String, Object> result) {

        public Receipt {
            result = immutable(result);
        }

        private static Map<String, Object> immutable(Map<String, ?> input) {
            var copy = new java.util.LinkedHashMap<String, Object>();
            input.forEach((key, value) -> copy.put(key, freeze(value)));
            return java.util.Collections.unmodifiableMap(copy);
        }

        @SuppressWarnings("unchecked")
        private static Object freeze(Object value) {
            if (value instanceof Map<?, ?> map) {
                return immutable((Map<String, ?>) map);
            }
            if (value instanceof List<?> list) {
                return list.stream().map(Receipt::freeze).toList();
            }
            return value;
        }
    }

    public record Proposal(
            String id,
            long expectedRevision,
            long policyGeneration,
            List<Operation> operations,
            String sourceExecutionId,
            String sourceMessageId,
            String reviewId,
            Instant createdAt) {

        public Proposal {
            operations = List.copyOf(operations);
        }
    }
}
