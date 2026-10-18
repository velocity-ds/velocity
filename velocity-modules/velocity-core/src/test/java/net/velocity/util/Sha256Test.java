package net.velocity.util;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Sha256Test {

    @Test
    void matchesTheKnownAnswerVectors() {
        assertThat(Sha256.hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(Sha256.hex(""))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(Sha256.hex(new byte[0])).isEqualTo(Sha256.hex(""));
    }

    @Test
    void textIsDigestedAsUtf8() {
        assertThat(Sha256.hex("hé")).isEqualTo(Sha256.hex("hé".getBytes(StandardCharsets.UTF_8)));
        assertThat(Sha256.hex("hé")).isNotEqualTo(Sha256.hex("hé".getBytes(StandardCharsets.ISO_8859_1)));
    }
}
