package net.velocity.agent.state;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrustedScopeTest {

    private static final String OWNER = "7b6a4a2e-1b2c-4d3e-8f90-0123456789ab";

    @Test
    void everyTransportComponentIsRequired() {
        assertThatThrownBy(() -> new TrustedScope(" ", "U", "C", "B", "slack"))
                .hasMessage("workspaceId must not be blank");
        assertThatThrownBy(() -> new TrustedScope("W", null, "C", "B", "slack"))
                .hasMessage("requesterId must not be blank");
        assertThatThrownBy(() -> new TrustedScope("W", "U", "", "B", "slack"))
                .hasMessage("channelId must not be blank");
        assertThatThrownBy(() -> new TrustedScope("W", "U", "C", " ", "slack"))
                .hasMessage("botId must not be blank");
        assertThatThrownBy(() -> new TrustedScope("W", "U", "C", "B", null))
                .hasMessage("origin must not be blank");
    }

    @Test
    void ownerAccountIdsMustBeCanonicalUuids() {
        assertThatThrownBy(() -> new TrustedScope("W", "U", "C", "B", "slack", "not-a-uuid"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrustedScope("W", "U", "C", "B", "slack", OWNER.toUpperCase()))
                .hasMessage("Invalid ownerAccountId");
        assertThat(new TrustedScope("W", "U", "C", "B", "slack", OWNER).ownerAccountId()).isEqualTo(OWNER);
    }

    @Test
    void unownedScopesCannotActForAnAccount() {
        var unowned = new TrustedScope("W", "U", "C", "B", "slack");
        assertThat(unowned.ownerAccountId()).isNull();
        assertThatThrownBy(unowned::requireOwnerAccountId)
                .isInstanceOf(SecurityException.class)
                .hasMessage("A verified Velocity account is required");
        assertThat(new TrustedScope("W", "U", "C", "B", "slack", OWNER).requireOwnerAccountId())
                .isEqualTo(OWNER);
    }

    @Test
    void sameOwnerComparesAccountsNotTransportContext() {
        var slack = new TrustedScope("W", "U", "C", "B", "slack", OWNER);
        var console = new TrustedScope("local", "operator", "console", "velocity", "http", OWNER);
        var other = new TrustedScope("W", "U", "C", "B", "slack", "0f1e2d3c-4b5a-4978-8695-a4b3c2d1e0f9");
        var unowned = new TrustedScope("W", "U", "C", "B", "slack");
        assertThat(slack.sameOwner(console)).isTrue();
        assertThat(slack.sameOwner(other)).isFalse();
        assertThat(slack.sameOwner(unowned)).isFalse();
        assertThat(unowned.sameOwner(unowned)).isFalse();
        assertThat(slack.sameOwner(null)).isFalse();
    }

    @Test
    void withOriginKeepsEveryOtherComponent() {
        var scope = new TrustedScope("W", "U", "C", "B", "slack", OWNER);
        var moved = scope.withOrigin("scheduler");
        assertThat(moved.origin()).isEqualTo("scheduler");
        assertThat(moved.ownerAccountId()).isEqualTo(OWNER);
        assertThat(moved.workspaceId()).isEqualTo("W");
        assertThatThrownBy(() -> scope.withOrigin(" ")).hasMessage("origin must not be blank");
    }
}
