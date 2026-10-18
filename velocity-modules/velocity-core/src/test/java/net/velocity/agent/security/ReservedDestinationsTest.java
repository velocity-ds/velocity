package net.velocity.agent.security;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.velocity.agent.spi.ReservedDestinationPolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservedDestinationsTest {

    private static final ReservedDestinationPolicy MAIL = policy(
            "mail.example.com",
            "https://mail.example.com/");
    private static final ReservedDestinationPolicy LEDGER = policy(
            "ledger.example.com",
            "https://ledger.example.com/");
    private static final ReservedDestinationPolicy BROKEN = new ReservedDestinationPolicy() {

        @Override
        public boolean browserHostBlocked(String host) {
            throw new IllegalStateException("boom");
        }

        @Override
        public boolean credentialDestinationBlocked(String destination) {
            throw new IllegalStateException("boom");
        }
    };

    @Test
    void nothingIsReservedWithoutPolicies() {
        var reserved = new ReservedDestinations(List.of());
        assertThat(reserved.browserHostBlocked("mail.example.com")).isFalse();
        assertThat(reserved.credentialDestinationBlocked("https://mail.example.com/")).isFalse();
    }

    @Test
    void aDestinationIsReservedWhenAnyPolicyReservesIt() {
        var reserved = new ReservedDestinations(List.of(MAIL, LEDGER));
        assertThat(reserved.browserHostBlocked("mail.example.com")).isTrue();
        assertThat(reserved.browserHostBlocked("ledger.example.com")).isTrue();
        assertThat(reserved.browserHostBlocked("public.example.com")).isFalse();
        assertThat(reserved.credentialDestinationBlocked("https://ledger.example.com/")).isTrue();
        assertThat(reserved.credentialDestinationBlocked("https://public.example.com/")).isFalse();
    }

    @Test
    void aThrowingPolicyFailsClosed() {
        var reserved = new ReservedDestinations(List.of(MAIL, BROKEN));
        assertThat(reserved.browserHostBlocked("public.example.com")).isTrue();
        assertThat(reserved.credentialDestinationBlocked("https://public.example.com/")).isTrue();
    }

    @Test
    void nullPoliciesAreRejectedAtConstruction() {
        assertThatThrownBy(() -> new ReservedDestinations(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ReservedDestinations(Collections.singletonList(null)))
                .isInstanceOf(NullPointerException.class);
    }

    private static ReservedDestinationPolicy policy(String host, String destination) {
        return new ReservedDestinationPolicy() {

            @Override
            public boolean browserHostBlocked(String candidate) {
                return host.equals(candidate);
            }

            @Override
            public boolean credentialDestinationBlocked(String candidate) {
                return destination.equals(candidate);
            }
        };
    }
}
