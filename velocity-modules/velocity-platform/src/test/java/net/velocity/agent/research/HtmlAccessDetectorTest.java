package net.velocity.agent.research;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlAccessDetectorTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "<html><body><form action=\"/login\"><input type=\"text\" name=\"u\"><input type=\"password\" name=\"p\"></form></body></html>",
            "<html><head><title>Sign in - Example</title></head><body><p>Welcome</p></body></html>",
            "<html><head><title>403 Forbidden</title></head><body></body></html>",
            "<html><body><div class=\"g-recaptcha\" data-sitekey=\"x\"></div></body></html>",
            "<html><body><iframe src=\"https://www.google.com/recaptcha/api2/anchor\"></iframe></body></html>",
            "<html><body><form id=\"challenge-form\"></form></body></html>",
            "<html><body><h1>Checking your browser</h1><p>Please verify you are human to continue.</p></body></html>",
            "<html><body><p>This link has expired.</p></body></html>",
            "<input type=\"PASSWORD\" name=\"p\">"})
    void recognizesAccessPages(String html) {
        assertThat(HtmlAccessDetector.isAccessPage(html)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<html><head><title>Quarterly report</title></head><body><p>Revenue grew 4%.</p></body></html>",
            "<html><body><form><input type=\"search\" name=\"q\"></form><p>Results</p></body></html>",
            "<html><body><p>Our logins page is documented elsewhere.</p></body></html>",
            "plain text without any markup",
            ""})
    void ordinaryContentIsNotAnAccessPage(String html) {
        assertThat(HtmlAccessDetector.isAccessPage(html)).isFalse();
    }

    @Test
    void nullAndScriptContentAreIgnored() {
        assertThat(HtmlAccessDetector.isAccessPage(null)).isFalse();
        assertThat(
                HtmlAccessDetector.isAccessPage(
                        "<html><head><script>document.write('verify you are human');</script>"
                                + "<style>.x:before{content:'sign in'}</style></head><body><p>Hello</p></body></html>"))
                .isFalse();
        assertThat(
                HtmlAccessDetector.isAccessPage(
                        "<html><head><script>var a = 1;</script></head><body><p>Verify you are human</p></body></html>"))
                .isTrue();
    }
}
