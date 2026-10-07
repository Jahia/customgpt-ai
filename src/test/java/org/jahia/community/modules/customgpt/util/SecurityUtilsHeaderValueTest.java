package org.jahia.community.modules.customgpt.util;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SecurityUtils#normalizeHeaderValue(String)}, which guards every admin-supplied value that
 * is sent as an HTTP header.
 *
 * <p>OkHttp refuses to build a request whose header value contains a character outside {@code  ..~}
 * (plus tab) and throws {@link IllegalArgumentException}. Letting such a value through would turn a typo in the
 * {@code .cfg} into an exception on the indexation thread for every single page. Rejecting it here costs the
 * custom header and nothing else.
 *
 * <p>JUnit 4 deliberately: the jahia-modules parent pins the {@code surefire-junit4} provider, and JUnit 5 methods
 * are silently not discovered.
 */
public class SecurityUtilsHeaderValueTest {

    @Test
    public void normalizeHeaderValue_keepsAnOrdinaryUserAgent() {
        final String ua = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

        assertThat(SecurityUtils.normalizeHeaderValue(ua)).isEqualTo(ua);
    }

    @Test
    public void normalizeHeaderValue_trimsSurroundingWhitespace() {
        assertThat(SecurityUtils.normalizeHeaderValue("  JahiaIndexer/1.0  ")).isEqualTo("JahiaIndexer/1.0");
    }

    @Test
    public void normalizeHeaderValue_keepsAnInternalTab() {
        // Tab is legal inside a header value; only the surrounding whitespace is trimmed.
        assertThat(SecurityUtils.normalizeHeaderValue("a\tb")).isEqualTo("a\tb");
    }

    @Test
    public void normalizeHeaderValue_returnsEmptyForNullOrBlank() {
        assertThat(SecurityUtils.normalizeHeaderValue(null)).isEmpty();
        assertThat(SecurityUtils.normalizeHeaderValue("")).isEmpty();
        assertThat(SecurityUtils.normalizeHeaderValue("   ")).isEmpty();
    }

    @Test
    public void normalizeHeaderValue_rejectsCrLfSoHeadersCannotBeSplit() {
        // Response/request splitting: a newline would let a .cfg value inject a second header.
        assertThat(SecurityUtils.normalizeHeaderValue("Bot/1.0\r\nX-Injected: yes")).isEmpty();
        assertThat(SecurityUtils.normalizeHeaderValue("Bot/1.0\nX-Injected: yes")).isEmpty();
    }

    @Test
    public void normalizeHeaderValue_rejectsOtherControlCharacters() {
        assertThat(SecurityUtils.normalizeHeaderValue("Bot/1.0\u0000")).isEmpty();
        assertThat(SecurityUtils.normalizeHeaderValue("Bot/\u007f1.0")).isEmpty();
    }

    @Test
    public void normalizeHeaderValue_rejectsNonAsciiRatherThanMisencodeIt() {
        // OkHttp would throw on these; a mojibake User-Agent is no use anyway.
        assertThat(SecurityUtils.normalizeHeaderValue("Navigateur/1.0 (éàü)")).isEmpty();
    }
}
