package net.velocity.agent.text;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class StoredTextSafetyTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "key sk-abcdefghijklmnop",
            "xoxb-12345678-abcdefgh",
            "AKIAABCDEFGHIJKLMNOP",
            "-----BEGIN RSA PRIVATE KEY-----",
            "password: hunter22",
            "api_key = abcdef1",
            "Access Token= 0123456789",
            "secret:topsecret",
            "Authorization: dXNlcjpwYXNzd29yZA==",
            "Bearer abcdefghijklmnop"})
    void recognizesCommonSecretShapes(String text) {
        assertThat(StoredTextSafety.containsRecognizableSecret(text)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "The password policy requires eight characters",
            "sk-short",
            "Rotate the API key quarterly",
            "bearer of bad news",
            "Secret Santa",
            ""})
    void ignoresOrdinaryProse(String text) {
        assertThat(StoredTextSafety.containsRecognizableSecret(text)).isFalse();
    }

    @Test
    void controlCharactersOtherThanNewlineAndTabAreUnsupported() {
        assertThat(StoredTextSafety.unicodeIssue("plain text\nwith\ttabs", false))
                .isEqualTo(StoredTextSafety.UnicodeIssue.NONE);
        assertThat(StoredTextSafety.unicodeIssue("bell\u0007", false))
                .isEqualTo(StoredTextSafety.UnicodeIssue.UNSUPPORTED_CHARACTER);
        assertThat(StoredTextSafety.unicodeIssue("line\r\n", false))
                .isEqualTo(StoredTextSafety.UnicodeIssue.UNSUPPORTED_CHARACTER);
        assertThat(StoredTextSafety.unicodeIssue("line\r\n", true))
                .isEqualTo(StoredTextSafety.UnicodeIssue.NONE);
    }

    @Test
    void surrogatesMustBePaired() {
        assertThat(StoredTextSafety.unicodeIssue("ok 😀", false))
                .isEqualTo(StoredTextSafety.UnicodeIssue.NONE);
        assertThat(StoredTextSafety.unicodeIssue("trailing \uD83D", false))
                .isEqualTo(StoredTextSafety.UnicodeIssue.UNPAIRED_HIGH_SURROGATE);
        assertThat(StoredTextSafety.unicodeIssue("broken \uD83Dx", false))
                .isEqualTo(StoredTextSafety.UnicodeIssue.UNPAIRED_HIGH_SURROGATE);
        assertThat(StoredTextSafety.unicodeIssue("lone \uDE00", false))
                .isEqualTo(StoredTextSafety.UnicodeIssue.UNSUPPORTED_CHARACTER);
    }
}
