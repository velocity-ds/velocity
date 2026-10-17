package net.velocity.agent.http;

import java.time.Clock;
import java.util.Objects;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import net.velocity.agent.accounts.Account;
import net.velocity.agent.accounts.AccountRepository;
import net.velocity.agent.config.LocalAccountProperties;
import net.velocity.agent.state.TrustedScope;

/**
 * The one account the console and the operator API act as, available only behind the local operator transport
 * boundary. Its identity comes from trusted configuration ({@link LocalAccountProperties}), never from a request:
 * the Slack workspace and user of the installation's owner, or the built-in console-only identity. The account
 * record is created on first use with the same registration path a verified Slack message uses, and the stored
 * identity is checked on every request.
 */
@Component
public final class LocalAccountScope {

    private final AccountRepository accounts;
    private final LocalAccountProperties properties;
    private final Clock clock;
    private volatile String ownerAccountId;

    public LocalAccountScope(AccountRepository accounts, LocalAccountProperties properties, Clock clock) {
        this.accounts = Objects.requireNonNull(accounts);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
    }

    public TrustedScope scope() {
        Account account = resolve();
        return new TrustedScope(
                account.slackWorkspaceId(),
                account.slackUserId(),
                "operator",
                "velocity",
                "local",
                account.id());
    }

    private Account resolve() {
        try {
            String id = ownerAccountId;
            Account account = id == null ? null : accounts.find(id).filter(this::configured).orElse(null);
            if (account == null) {
                account = accounts
                        .resolveOrCreateSlack(properties.workspaceId(), properties.userId(), clock.instant());
                ownerAccountId = account.id();
            }
            return account;
        } catch (DataAccessException unavailable) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "The local account is unavailable",
                    unavailable);
        }
    }

    private boolean configured(Account account) {
        return properties.workspaceId().equals(account.slackWorkspaceId())
                && properties.userId().equals(account.slackUserId());
    }
}
