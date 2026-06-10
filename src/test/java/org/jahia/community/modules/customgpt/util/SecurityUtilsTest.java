package org.jahia.community.modules.customgpt.util;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SecurityUtils} — the write-only-secret masking contract, the HTTPS gate that protects
 * credentials in transit, the shared base-URL resolver, and log-injection sanitisation.
 *
 * <p>Written with JUnit 4 ({@code org.junit.Test}) because the jahia-modules parent pins the
 * {@code surefire-junit4} provider; JUnit 5 (jupiter) test methods are silently <em>not discovered</em> and report
 * "Tests run: 0".
 */
public class SecurityUtilsTest {

    // ---- maskSecretForDisplay ----

    @Test
    public void maskSecretForDisplay_returnsEmptyWhenNull() {
        assertThat(SecurityUtils.maskSecretForDisplay(null)).isEmpty();
    }

    @Test
    public void maskSecretForDisplay_returnsEmptyWhenBlank() {
        assertThat(SecurityUtils.maskSecretForDisplay("")).isEmpty();
    }

    @Test
    public void maskSecretForDisplay_returnsPlaceholderNeverRealValueWhenSet() {
        assertThat(SecurityUtils.maskSecretForDisplay("sk-super-secret-token"))
                .isEqualTo(SecurityUtils.SECRET_PLACEHOLDER)
                .doesNotContain("secret");
    }

    // ---- isMaskedPlaceholder ----

    @Test
    public void isMaskedPlaceholder_recognisesPlaceholderEchoedBack() {
        assertThat(SecurityUtils.isMaskedPlaceholder(SecurityUtils.SECRET_PLACEHOLDER)).isTrue();
    }

    @Test
    public void isMaskedPlaceholder_nullIsNotPlaceholder() {
        assertThat(SecurityUtils.isMaskedPlaceholder(null)).isFalse();
    }

    @Test
    public void isMaskedPlaceholder_emptyStillMeansClearTheSecret() {
        assertThat(SecurityUtils.isMaskedPlaceholder("")).isFalse();
    }

    @Test
    public void isMaskedPlaceholder_genuinelyNewValueIsNotPlaceholder() {
        assertThat(SecurityUtils.isMaskedPlaceholder("a-brand-new-token")).isFalse();
    }

    // ---- isHttpsUrl ----

    @Test
    public void isHttpsUrl_acceptsWellFormedHttpsWithHost() {
        assertThat(SecurityUtils.isHttpsUrl("https://app.customgpt.ai/api/v1")).isTrue();
    }

    @Test
    public void isHttpsUrl_isCaseInsensitiveAndTrimsWhitespace() {
        assertThat(SecurityUtils.isHttpsUrl("  HTTPS://app.customgpt.ai/api/v1  ")).isTrue();
    }

    @Test
    public void isHttpsUrl_rejectsCleartextHttp() {
        assertThat(SecurityUtils.isHttpsUrl("http://app.customgpt.ai/api/v1")).isFalse();
    }

    @Test
    public void isHttpsUrl_rejectsNonHttpScheme() {
        assertThat(SecurityUtils.isHttpsUrl("ftp://app.customgpt.ai")).isFalse();
        assertThat(SecurityUtils.isHttpsUrl("file:///etc/passwd")).isFalse();
    }

    @Test
    public void isHttpsUrl_rejectsHostlessSchemelessAndMalformed() {
        assertThat(SecurityUtils.isHttpsUrl("https://")).isFalse();
        assertThat(SecurityUtils.isHttpsUrl("app.customgpt.ai/api/v1")).isFalse();
        assertThat(SecurityUtils.isHttpsUrl("not a url")).isFalse();
    }

    @Test
    public void isHttpsUrl_rejectsNullAndEmpty() {
        assertThat(SecurityUtils.isHttpsUrl(null)).isFalse();
        assertThat(SecurityUtils.isHttpsUrl("")).isFalse();
    }

    // ---- resolveHttpsBaseUrl ----

    private static final String DEFAULT_BASE = "https://app.customgpt.ai/api/v1";

    @Test
    public void resolveHttpsBaseUrl_fallsBackToDefaultWhenBlank() {
        assertThat(SecurityUtils.resolveHttpsBaseUrl("", DEFAULT_BASE)).isEqualTo(DEFAULT_BASE);
        assertThat(SecurityUtils.resolveHttpsBaseUrl(null, DEFAULT_BASE)).isEqualTo(DEFAULT_BASE);
    }

    @Test
    public void resolveHttpsBaseUrl_stripsSingleTrailingSlash() {
        assertThat(SecurityUtils.resolveHttpsBaseUrl("https://host/api/v1/", DEFAULT_BASE))
                .isEqualTo("https://host/api/v1");
    }

    @Test
    public void resolveHttpsBaseUrl_keepsWellFormedHttps() {
        assertThat(SecurityUtils.resolveHttpsBaseUrl("https://host/api/v1", DEFAULT_BASE))
                .isEqualTo("https://host/api/v1");
    }

    @Test
    public void resolveHttpsBaseUrl_rejectsHttpSoTokenCannotLeakOverCleartext() {
        assertThatThrownBy(() -> SecurityUtils.resolveHttpsBaseUrl("http://evil.example/api", DEFAULT_BASE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("https");
    }

    @Test
    public void resolveHttpsBaseUrl_rejectsSchemelessOrMalformed() {
        assertThatThrownBy(() -> SecurityUtils.resolveHttpsBaseUrl("app.customgpt.ai/api", DEFAULT_BASE))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---- sanitizeForLog ----

    @Test
    public void sanitizeForLog_returnsNullUnchanged() {
        assertThat(SecurityUtils.sanitizeForLog(null)).isNull();
    }

    @Test
    public void sanitizeForLog_leavesCleanValueUntouched() {
        assertThat(SecurityUtils.sanitizeForLog("project-123")).isEqualTo("project-123");
    }

    @Test
    public void sanitizeForLog_stripsCrLfToPreventLogForging() {
        final String forged = "real\r\nINFO admin granted access";
        assertThat(SecurityUtils.sanitizeForLog(forged))
                .doesNotContain("\r")
                .doesNotContain("\n")
                .isEqualTo("real__INFO admin granted access");
    }

    @Test
    public void sanitizeForLog_replacesOtherIsoControlChars() {
        assertThat(SecurityUtils.sanitizeForLog("a\tb")).isEqualTo("a_b");
    }
}
