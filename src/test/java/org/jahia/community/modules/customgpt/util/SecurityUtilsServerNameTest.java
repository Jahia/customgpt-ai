package org.jahia.community.modules.customgpt.util;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SecurityUtils#normalizeServerName(String)} — the gate every configured indexation server
 * name passes through before it can replace the host derived from a site's {@code sitemapIndexURL}.
 *
 * <p>The normalised value is load-bearing twice over: the module fetches the rendered page from it (carrying the
 * Jahia Basic-auth credentials) and stores it as the citation URL in CustomGPT. A malformed value must therefore
 * be rejected outright rather than half-applied, and an internal address must be refused for the same SSRF reason
 * {@link Utils#getHostName} already refuses one coming from {@code sitemapIndexURL}.
 *
 * <p>JUnit 4 ({@code org.junit.Test}) because the jahia-modules parent pins the {@code surefire-junit4} provider;
 * jupiter methods are silently not discovered.
 */
public class SecurityUtilsServerNameTest {

    // ---- accepted and normalised ----

    @Test
    public void normalizeServerName_keepsAWellFormedHttpsBase() {
        assertThat(SecurityUtils.normalizeServerName("https://academypp.jahia.com"))
                .isEqualTo("https://academypp.jahia.com");
    }

    @Test
    public void normalizeServerName_stripsATrailingSlash() {
        // Arrange: the shape an admin most often pastes out of a browser address bar
        final String configured = "https://academypp.jahia.com/";

        // Act
        final String normalized = SecurityUtils.normalizeServerName(configured);

        // Assert: a trailing slash would double up against the rewritten path ("//home.html")
        assertThat(normalized).isEqualTo("https://academypp.jahia.com");
    }

    @Test
    public void normalizeServerName_dropsAnyPathSoASitemapUrlCanBePastedVerbatim() {
        assertThat(SecurityUtils.normalizeServerName("https://academypp.jahia.com/sitemap.xml"))
                .isEqualTo("https://academypp.jahia.com");
    }

    @Test
    public void normalizeServerName_keepsAnExplicitPort() {
        assertThat(SecurityUtils.normalizeServerName("https://academypp.jahia.com:8443/"))
                .isEqualTo("https://academypp.jahia.com:8443");
    }

    @Test
    public void normalizeServerName_lowercasesSchemeAndHost() {
        assertThat(SecurityUtils.normalizeServerName("HTTPS://Academy.Jahia.COM"))
                .isEqualTo("https://academy.jahia.com");
    }

    @Test
    public void normalizeServerName_acceptsHttpBecauseTheExistingSitemapHostMayBeHttpToo() {
        // Basic auth is skipped downstream for a non-https render URL (buildRenderRequest), so http is usable
        // for a plain local deployment without becoming a credential leak.
        assertThat(SecurityUtils.normalizeServerName("http://localhost.example.org"))
                .isEqualTo("http://localhost.example.org");
    }

    @Test
    public void normalizeServerName_trimsSurroundingWhitespace() {
        assertThat(SecurityUtils.normalizeServerName("  https://academypp.jahia.com  "))
                .isEqualTo("https://academypp.jahia.com");
    }

    // ---- rejected: nothing configured ----

    @Test
    public void normalizeServerName_returnsEmptyWhenNull() {
        assertThat(SecurityUtils.normalizeServerName(null)).isEmpty();
    }

    @Test
    public void normalizeServerName_returnsEmptyWhenBlank() {
        assertThat(SecurityUtils.normalizeServerName("   ")).isEmpty();
    }

    // ---- rejected: not a usable absolute base URL ----

    @Test
    public void normalizeServerName_rejectsABareHostnameWithNoScheme() {
        // "academypp.jahia.com" parses as a relative URI with no host at all; concatenating it would produce
        // "academypp.jahia.com/home.html", which OkHttp cannot even build a request from.
        assertThat(SecurityUtils.normalizeServerName("academypp.jahia.com")).isEmpty();
    }

    @Test
    public void normalizeServerName_rejectsASchemeRelativeUrl() {
        assertThat(SecurityUtils.normalizeServerName("//academypp.jahia.com")).isEmpty();
    }

    @Test
    public void normalizeServerName_rejectsANonHttpScheme() {
        assertThat(SecurityUtils.normalizeServerName("ftp://academypp.jahia.com")).isEmpty();
    }

    @Test
    public void normalizeServerName_rejectsASchemeWithNoHost() {
        assertThat(SecurityUtils.normalizeServerName("https://")).isEmpty();
    }

    @Test
    public void normalizeServerName_rejectsAnUnparseableValue() {
        assertThat(SecurityUtils.normalizeServerName("https://host name/with spaces")).isEmpty();
    }

    // ---- rejected: internal target (SSRF) ----

    @Test
    public void normalizeServerName_rejectsLoopback() {
        assertThat(SecurityUtils.normalizeServerName("https://127.0.0.1")).isEmpty();
    }

    @Test
    public void normalizeServerName_rejectsAPrivateRangeWithAPort() {
        assertThat(SecurityUtils.normalizeServerName("https://10.1.2.3:8080")).isEmpty();
    }

    @Test
    public void normalizeServerName_rejectsIpv6Loopback() {
        assertThat(SecurityUtils.normalizeServerName("https://[::1]")).isEmpty();
    }

    @Test
    public void normalizeServerName_rejectsIpv4MappedIpv6Loopback() {
        assertThat(SecurityUtils.normalizeServerName("https://[::ffff:127.0.0.1]")).isEmpty();
    }

    @Test
    public void normalizeServerName_rejectsTheDotlessDecimalFormOfLoopback() {
        // java.net.URI happily returns "2130706433" as the host, and InetAddress collapses it to 127.0.0.1 —
        // so this MUST be range-checked rather than waved through as an opaque hostname.
        assertThat(SecurityUtils.normalizeServerName("https://2130706433")).isEmpty();
    }

    @Test
    public void normalizeServerName_dropsUserinfoSoACredentialCannotBeSmuggledIntoTheIndexedUrl() {
        // The host is what both the validator and OkHttp act on; the userinfo is discarded rather than carried
        // into the citation URL stored in CustomGPT.
        assertThat(SecurityUtils.normalizeServerName("https://user:pass@academypp.jahia.com"))
                .isEqualTo("https://academypp.jahia.com");
    }

    @Test
    public void normalizeServerName_rejectsUserinfoPointingAtAnInternalHost() {
        assertThat(SecurityUtils.normalizeServerName("https://academypp.jahia.com@127.0.0.1")).isEmpty();
    }
}
