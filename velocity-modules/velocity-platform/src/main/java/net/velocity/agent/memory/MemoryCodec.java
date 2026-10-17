package net.velocity.agent.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.bson.RawBsonDocument;
import org.bson.codecs.DocumentCodec;

public final class MemoryCodec {

    private MemoryCodec() {
    }

    public static Document encode(MemoryStore store) {
        return new Document("_id", store.ownerAccountId() + ":" + store.target())
                .append("owner_account_id", store.ownerAccountId())
                .append("target", store.target()).append("format_version", store.formatVersion())
                .append("revision", store.revision()).append("enabled", store.enabled())
                .append("policy_generation", store.policyGeneration())
                .append(
                        "suppressed_through",
                        store.suppressedThrough() == null ? null : store.suppressedThrough().toString())
                .append("entries", store.entries().stream().map(MemoryCodec::entry).toList())
                .append(
                        "receipts",
                        store.receipts().stream().map(
                                receipt -> new Document("identity", receipt.identity())
                                        .append("digest", receipt.digest())
                                        .append("binding_digest", receipt.bindingDigest())
                                        .append("execution_id", receipt.executionId())
                                        .append("result", document(receipt.result())))
                                .toList())
                .append("proposals", store.proposals().stream().map(MemoryCodec::proposal).toList());
    }

    public static MemoryStore decode(Document value) {
        try {
            var store = new MemoryStore(
                    value.getString("owner_account_id"),
                    value.getString("target"),
                    Math.toIntExact(number(value, "format_version")),
                    number(value, "revision"),
                    value.getBoolean("enabled"),
                    number(value, "policy_generation"),
                    value.getString("suppressed_through") == null
                            ? null
                            : Instant.parse(value.getString("suppressed_through")),
                    value.getList("entries", Document.class).stream().map(
                            entry -> new MemoryStore.Entry(
                                    entry.getString("id"),
                                    entry.getString("text"),
                                    entry.getString("evidence_kind"),
                                    entry.getString("source_execution_id"),
                                    entry.getString("source_message_id"),
                                    Instant.parse(entry.getString("created_at")),
                                    Instant.parse(entry.getString("updated_at"))))
                            .toList(),
                    value.getList("receipts", Document.class).stream().map(
                            receipt -> new MemoryStore.Receipt(
                                    receipt.getString("identity"),
                                    receipt.getString("digest"),
                                    receipt.getString("binding_digest"),
                                    receipt.getString("execution_id"),
                                    json(receipt.get("result", Document.class))))
                            .toList(),
                    value.getList("proposals", Document.class).stream()
                            .map(
                                    proposal -> new MemoryStore.Proposal(
                                            proposal.getString("id"),
                                            number(proposal, "expected_revision"),
                                            number(proposal, "policy_generation"),
                                            proposal.getList("operations", Document.class).stream()
                                                    .map(
                                                            operation -> new MemoryStore.Operation(
                                                                    operation.getString("action"),
                                                                    operation.getString("entry_id"),
                                                                    operation.getString("content")))
                                                    .toList(),
                                            proposal.getString("source_execution_id"),
                                            proposal.getString("source_message_id"),
                                            proposal.getString("review_id"),
                                            Instant.parse(proposal.getString("created_at"))))
                            .toList());
            if (!value.getString("_id").equals(store.ownerAccountId() + ":" + store.target())) {
                throw new IllegalStateException("Invalid memory identity");
            }
            MemoryService.validateStore(store);
            return store;
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Invalid persisted memory store");
        }
    }

    static Document entry(MemoryStore.Entry entry) {
        return new Document("id", entry.id()).append("text", entry.text())
                .append("evidence_kind", entry.evidenceKind())
                .append("source_execution_id", entry.sourceExecutionId())
                .append("source_message_id", entry.sourceMessageId())
                .append("created_at", entry.createdAt().toString())
                .append("updated_at", entry.updatedAt().toString());
    }

    static Document proposal(MemoryStore.Proposal proposal) {
        return new Document("id", proposal.id()).append("expected_revision", proposal.expectedRevision())
                .append("policy_generation", proposal.policyGeneration())
                .append(
                        "operations",
                        proposal.operations().stream()
                                .map(
                                        operation -> new Document("action", operation.action())
                                                .append("entry_id", operation.entryId())
                                                .append("content", operation.content()))
                                .toList())
                .append("source_execution_id", proposal.sourceExecutionId())
                .append("source_message_id", proposal.sourceMessageId())
                .append("review_id", proposal.reviewId())
                .append("created_at", proposal.createdAt().toString());
    }

    static int size(MemoryStore store) {
        return new RawBsonDocument(encode(store), new DocumentCodec()).getByteBuffer().remaining();
    }

    private static long number(Document value, String key) {
        Object number = value.get(key);
        if (!(number instanceof Integer || number instanceof Long)) {
            throw new IllegalArgumentException("Invalid integer");
        }
        return ((Number) number).longValue();
    }

    private static Document document(Map<String, Object> input) {
        Document result = new Document();
        input.forEach((key, value) -> result.put(key, bson(value)));
        return result;
    }

    static Map<String, Object> json(Map<String, ?> input) {
        var result = new LinkedHashMap<String, Object>();
        input.forEach((key, value) -> result.put(key, jsonValue(value)));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Object jsonValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return json((Map<String, ?>) map);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(MemoryCodec::jsonValue).toList();
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Object bson(Object value) {
        if (value instanceof Map<?, ?> map) {
            return document((Map<String, Object>) map);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(MemoryCodec::bson).toList();
        }
        return value;
    }
}
