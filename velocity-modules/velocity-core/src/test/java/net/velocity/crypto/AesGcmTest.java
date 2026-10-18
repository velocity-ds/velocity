package net.velocity.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmTest {

    private static final String ACTIVE = randomKey();
    private static final String PREVIOUS = randomKey();

    @Test
    void loadsTheActiveKeyAndEveryPreviousKey() {
        assertThat(AesGcm.keys("k2", ACTIVE, "")).containsOnlyKeys("k2");
        assertThat(AesGcm.keys("k2", ACTIVE, "   ")).containsOnlyKeys("k2");
        var keys = AesGcm.keys("k2", ACTIVE, "{\"k1\":\"" + PREVIOUS + "\"}");
        assertThat(keys).containsOnlyKeys("k1", "k2");
        assertThat(keys.get("k2").getAlgorithm()).isEqualTo("AES");
        assertThat(keys.get("k2").getEncoded()).isEqualTo(Base64.getDecoder().decode(ACTIVE));
    }

    @Test
    void rejectsMalformedKeyConfigurationWithOneSafeMessage() {
        for (String[] invalid : new String[][]{
                {"bad id!", ACTIVE, ""},
                {null, ACTIVE, ""},
                {"k2", "short", ""},
                {"k2", null, ""},
                {"k2", ACTIVE, null},
                {"k2", ACTIVE, "not json"},
                {"k2", ACTIVE, "[]"},
                {"k2", ACTIVE, "{\"k2\":\"" + PREVIOUS + "\"}"},
                {"k2", ACTIVE, "{\"bad id\":\"" + PREVIOUS + "\"}"},
                {"k2", ACTIVE, "{\"k1\":1}"},
                {"k2", ACTIVE, "{\"k1\":\"" + PREVIOUS.substring(1) + "\"}"},
                {"k2", ACTIVE, "{\"k1\":\"" + PREVIOUS + "\",\"k1\":\"" + PREVIOUS + "\"}"}}) {
            assertThatThrownBy(() -> AesGcm.keys(invalid[0], invalid[1], invalid[2]))
                    .as(
                            String.join(
                                    " | ",
                                    String.valueOf(invalid[0]),
                                    String.valueOf(invalid[1]),
                                    String.valueOf(invalid[2])))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Invalid encryption key configuration");
        }
    }

    @Test
    void rejectsMoreThanFifteenPreviousKeys() {
        var previous = new StringBuilder("{");
        for (int index = 0; index < 16; index++) {
            previous.append(index == 0 ? "" : ",").append("\"old").append(index).append("\":\"")
                    .append(randomKey())
                    .append('"');
        }
        previous.append('}');
        assertThatThrownBy(() -> AesGcm.keys("k2", ACTIVE, previous.toString()))
                .hasMessage("Invalid encryption key configuration");
    }

    @Test
    void encryptsWithAFreshNonceAndDecryptsWithTheSameKeyAndAssociatedData() throws GeneralSecurityException {
        SecretKeySpec key = AesGcm.keys("k", ACTIVE, "").get("k");
        byte[] plaintext = "credential bytes".getBytes(StandardCharsets.UTF_8);
        byte[] associated = "owner:abc|v1".getBytes(StandardCharsets.UTF_8);

        AesGcm.Encrypted first = AesGcm.encrypt(key, plaintext, associated);
        AesGcm.Encrypted second = AesGcm.encrypt(key, plaintext, associated);

        assertThat(Base64.getDecoder().decode(first.nonce())).hasSize(12);
        assertThat(first.nonce()).isNotEqualTo(second.nonce());
        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
        assertThat(AesGcm.decrypt(key, first.nonce(), first.ciphertext(), associated)).isEqualTo(plaintext);
        assertThat(AesGcm.decrypt(key, second.nonce(), second.ciphertext(), associated)).isEqualTo(plaintext);
    }

    @Test
    void tamperedInputsFailAuthentication() throws GeneralSecurityException {
        SecretKeySpec key = AesGcm.keys("k", ACTIVE, "").get("k");
        SecretKeySpec other = AesGcm.keys("k", PREVIOUS, "").get("k");
        byte[] associated = "owner:abc".getBytes(StandardCharsets.UTF_8);
        AesGcm.Encrypted encrypted = AesGcm
                .encrypt(key, "payload".getBytes(StandardCharsets.UTF_8), associated);

        assertThatThrownBy(() -> AesGcm.decrypt(other, encrypted.nonce(), encrypted.ciphertext(), associated))
                .isInstanceOf(GeneralSecurityException.class);
        assertThatThrownBy(
                () -> AesGcm.decrypt(key, encrypted.nonce(), encrypted.ciphertext(), "owner:xyz".getBytes()))
                .isInstanceOf(GeneralSecurityException.class);
        byte[] flipped = Base64.getDecoder().decode(encrypted.ciphertext());
        flipped[0] ^= 1;
        assertThatThrownBy(
                () -> AesGcm.decrypt(
                        key,
                        encrypted.nonce(),
                        Base64.getEncoder().encodeToString(flipped),
                        associated))
                .isInstanceOf(GeneralSecurityException.class);
        assertThatThrownBy(
                () -> AesGcm.decrypt(
                        key,
                        Base64.getEncoder().encodeToString(new byte[8]),
                        encrypted.ciphertext(),
                        associated))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid encryption payload");
        assertThatThrownBy(() -> AesGcm.decrypt(null, encrypted.nonce(), encrypted.ciphertext(), associated))
                .hasMessage("Invalid encryption payload");
    }

    private static String randomKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
