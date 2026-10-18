package net.velocity.agent.browser;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrowserContentRedactorTest {

    private final BrowserContentRedactor redactor = new BrowserContentRedactor();

    @Test
    void rememberedValuesAreRedactedInEveryCommonEncoding() {
        redactor.remember("p@ss w0rd!");
        assertThat(redactor.clean("raw p@ss w0rd! end")).isEqualTo("raw [redacted] end");
        assertThat(redactor.clean("url p%40ss+w0rd%21 end")).isEqualTo("url [redacted] end");
        assertThat(redactor.clean("url p%40ss%20w0rd%21 end")).isEqualTo("url [redacted] end");
        assertThat(redactor.clean("b64 cEBzcyB3MHJkIQ== end")).isEqualTo("b64 [redacted] end");
        redactor.remember("a<b&c");
        assertThat(redactor.clean("html a&lt;b&amp;c end")).isEqualTo("html [redacted] end");
        assertThat(redactor.containsSecret("has p@ss w0rd! inside")).isTrue();
        assertThat(redactor.containsSecret("nothing here")).isFalse();
        assertThat(redactor.containsSecret(null)).isFalse();
    }

    @Test
    void shortValuesAreOnlyRedactedAsWholeTokens() {
        redactor.remember("abc12");
        assertThat(redactor.clean("xabc12y")).isEqualTo("xabc12y");
        assertThat(redactor.clean("abc12 end")).isEqualTo("[redacted] end");
        assertThat(redactor.clean("(abc12)")).isEqualTo("([redacted])");
    }

    @Test
    void truncatedLongTokensAreStillRedacted() {
        redactor.remember("ABCDEFGHIJKLMNOPQRST");
        assertThat(redactor.clean("prefix ABCDEFGHIJKLMNO suffix")).isEqualTo("prefix [redacted] suffix");
        assertThat(redactor.clean("ABCDEFGHIJKLMNOPQRST")).isEqualTo("[redacted]");
        assertThat(redactor.clean("ABCDEFGHIJK")).isEqualTo("ABCDEFGHIJK");
    }

    @Test
    void secretLookingTokensAreRedactedEvenWhenUnknown() {
        assertThat(redactor.clean("Bearer abc.def-ghi")).isEqualTo("[redacted]");
        // The header form matches the "authorization:" key alternative first, which consumes only the
        // scheme word; the token after it is left for the known-value and truncated-token passes.
        assertThat(redactor.clean("Authorization: Bearer abc.def-ghi")).isEqualTo("[redacted] abc.def-ghi");
        assertThat(redactor.clean("jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJl end"))
                .isEqualTo("jwt [redacted] end");
        assertThat(redactor.clean("password=hunter2&next=1")).isEqualTo("[redacted]&next=1");
        assertThat(redactor.clean("api_key: k-123 and more")).isEqualTo("[redacted] and more");
        assertThat(redactor.clean("plain text stays")).isEqualTo("plain text stays");
        // Known values are removed without touching unknown tokens.
        assertThat(redactor.cleanKnownValues("Authorization: Bearer abc.def-ghi"))
                .isEqualTo("Authorization: Bearer abc.def-ghi");
    }

    @Test
    void urlTokensAreLearnedFromSecretLookingParameters() {
        redactor.rememberUrlTokens(
                "https://app.example/callback?code=AUTHCODE12345&state=st4te1234&foo=bar#access_token=tok_ABCDEFGH");
        assertThat(redactor.containsSecret("AUTHCODE12345")).isTrue();
        assertThat(redactor.containsSecret("st4te1234")).isTrue();
        assertThat(redactor.containsSecret("tok_ABCDEFGH")).isTrue();
        assertThat(redactor.containsSecret("bar")).isFalse();
        assertThatCode(() -> redactor.rememberUrlTokens("::not a url")).doesNotThrowAnyException();
        assertThatCode(() -> redactor.rememberUrlTokens("https://app.example/?code"))
                .doesNotThrowAnyException();
    }

    @Test
    void requiredValuesAreBoundedAndNeverSkipped() {
        assertThatThrownBy(() -> redactor.rememberRequired(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> redactor.rememberRequired("")).isInstanceOf(IllegalArgumentException.class);
        for (int index = 0; index < 6; index++) {
            redactor.rememberRequired("required-secret-" + index);
        }
        assertThatCode(() -> redactor.rememberRequired("required-secret-0")).doesNotThrowAnyException();
        assertThatThrownBy(() -> redactor.rememberRequired("required-secret-6"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Browser credential redaction capacity exceeded");
        assertThat(redactor.clean("value required-secret-3 here")).isEqualTo("value [redacted] here");
    }

    @Test
    void clearForgetsEverything() {
        redactor.remember("forget-me-please");
        redactor.rememberRequired("required-secret");
        assertThat(redactor.containsSecret("forget-me-please")).isTrue();
        redactor.clear();
        assertThat(redactor.containsSecret("forget-me-please")).isFalse();
        assertThat(redactor.containsSecret("required-secret")).isFalse();
        assertThat(redactor.clean("forget-me-please")).isEqualTo("forget-me-please");
    }
}
