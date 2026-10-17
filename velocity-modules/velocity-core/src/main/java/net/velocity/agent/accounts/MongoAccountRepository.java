package net.velocity.agent.accounts;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.mongodb.WriteConcern;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.ReturnDocument;

public final class MongoAccountRepository implements AccountRepository {

    public static final String COLLECTION = "accounts";
    private final MongoTemplate mongo;
    private volatile boolean indexed;

    public MongoAccountRepository(MongoTemplate mongo) {
        this.mongo = java.util.Objects.requireNonNull(mongo);
    }

    @Override
    public Optional<Account> find(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional
                .ofNullable(mongo.execute(COLLECTION, values -> values.find(new Document("_id", id)).first()))
                .map(MongoAccountRepository::account);
    }

    @Override
    public Account resolveOrCreateSlack(String workspaceId, String userId, Instant now) {
        Account.requireIdentity(workspaceId);
        Account.requireIdentity(userId);
        java.util.Objects.requireNonNull(now);
        indexes();
        Document identity = new Document("slack_workspace_id", workspaceId).append("slack_user_id", userId);
        Document update = new Document(
                "$setOnInsert",
                new Document("_id", UUID.randomUUID().toString())
                        .append("created_at", java.util.Date.from(now)))
                .append("$max", new Document("updated_at", java.util.Date.from(now)));
        try {
            return account(
                    mongo.execute(
                            COLLECTION,
                            values -> values.withWriteConcern(WriteConcern.ACKNOWLEDGED)
                                    .findOneAndUpdate(
                                            identity,
                                            update,
                                            new FindOneAndUpdateOptions().upsert(true)
                                                    .returnDocument(ReturnDocument.AFTER))));
        } catch (DuplicateKeyException raced) {
            // A different process/event won the unique identity insert. Read/update that same account.
            return account(
                    mongo.execute(
                            COLLECTION,
                            values -> values.findOneAndUpdate(
                                    identity,
                                    new Document(
                                            "$max",
                                            new Document("updated_at", java.util.Date.from(now))),
                                    new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER))));
        }
    }

    private synchronized void indexes() {
        if (indexed) {
            return;
        }
        mongo.execute(
                COLLECTION,
                values -> values.createIndex(
                        new Document("slack_workspace_id", 1).append("slack_user_id", 1),
                        new IndexOptions().unique(true).name("account_slack_identity")));
        indexed = true;
    }

    private static Account account(Document value) {
        if (value == null) {
            throw new IllegalStateException("Account registration did not persist");
        }
        return new Account(
                value.getString("_id"),
                value.getString("slack_workspace_id"),
                value.getString("slack_user_id"),
                value.getDate("created_at").toInstant(),
                value.getDate("updated_at").toInstant());
    }
}
