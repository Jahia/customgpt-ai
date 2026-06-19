package org.jahia.community.modules.customgpt.util;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Additional unit tests for {@link SecurityUtils} focussing on the SSRF guard
 * ({@link SecurityUtils#isInternalHost} and the IP-literal ranges rejected by
 * {@link SecurityUtils#isHttpsUrl}), plus the masked-placeholder substring-not-equals case
 * and dangerous URI schemes.
 *
 * The existing {@link SecurityUtilsTest} is left intact; these cases are added here so that
 * each file remains focused and below the 400-line guideline.
 */
public class SecurityUtilsAdditionalTest {

    // ---- isInternalHost: loopback ----

    @Test
    public void isInternalHost_loopbackIpv4_returnsTrue() {
        assertThat(SecurityUtils.isInternalHost("127.0.0.1")).isTrue();
    }

    @Test
    public void isInternalHost_loopbackIpv4_allOnes_returnsTrue() {
        assertThat(SecurityUtils.isInternalHost("127.255.255.255")).isTrue();
    }

    @Test
    public void isInternalHost_loopbackIpv6_returnsTrue() {
        assertThat(SecurityUtils.isInternalHost("::1")).isTrue();
    }

    @Test
    public void isInternalHost_loopbackIpv6_bracketed_returnsTrue() {
        // URI parser wraps IPv6 in brackets; isInternalHost must strip them
        assertThat(SecurityUtils.isInternalHost("[::1]")).isTrue();
    }

    // ---- isInternalHost: site-local (RFC 1918) ----

    @Test
    public void isInternalHost_rfc1918_10Block_returnsTrue() {
        assertThat(SecurityUtils.isInternalHost("10.0.0.1")).isTrue();
        assertThat(SecurityUtils.isInternalHost("10.255.255.254")).isTrue();
    }

    @Test
    public void isInternalHost_rfc1918_172_16Block_returnsTrue() {
        assertThat(SecurityUtils.isInternalHost("172.16.0.1")).isTrue();
        assertThat(SecurityUtils.isInternalHost("172.31.255.254")).isTrue();
    }

    @Test
    public void isInternalHost_rfc1918_192_168Block_returnsTrue() {
        assertThat(SecurityUtils.isInternalHost("192.168.1.1")).isTrue();
        assertThat(SecurityUtils.isInternalHost("192.168.0.1")).isTrue();
    }

    // ---- isInternalHost: link-local ----

    @Test
    public void isInternalHost_linkLocal_169_254_returnsTrue() {
        // AWS / GCP instance metadata endpoint
        assertThat(SecurityUtils.isInternalHost("169.254.169.254")).isTrue();
        assertThat(SecurityUtils.isInternalHost("169.254.0.1")).isTrue();
    }

    // ---- isInternalHost: public IP (not internal) ----

    @Test
    public void isInternalHost_publicIp_returnsFalse() {
        assertThat(SecurityUtils.isInternalHost("8.8.8.8")).isFalse();
        assertThat(SecurityUtils.isInternalHost("1.1.1.1")).isFalse();
    }

    // ---- isInternalHost: hostname (not an IP literal — never resolved) ----

    @Test
    public void isInternalHost_publicHostname_returnsFalse() {
        // Hostnames are never resolved; they are always accepted (not flagged as internal)
        assertThat(SecurityUtils.isInternalHost("app.customgpt.ai")).isFalse();
        assertThat(SecurityUtils.isInternalHost("localhost")).isFalse(); // no DNS → not flagged
    }

    @Test
    public void isInternalHost_nullOrEmpty_returnsFalse() {
        assertThat(SecurityUtils.isInternalHost(null)).isFalse();
        assertThat(SecurityUtils.isInternalHost("")).isFalse();
        assertThat(SecurityUtils.isInternalHost("   ")).isFalse();
    }

    // ---- isHttpsUrl: SSRF guard for private IPs ----

    @Test
    public void isHttpsUrl_loopbackIpLiteral_rejected() {
        assertThat(SecurityUtils.isHttpsUrl("https://127.0.0.1/api")).isFalse();
    }

    @Test
    public void isHttpsUrl_rfc1918_10Block_rejected() {
        assertThat(SecurityUtils.isHttpsUrl("https://10.0.0.1/api")).isFalse();
    }

    @Test
    public void isHttpsUrl_rfc1918_192_168_rejected() {
        assertThat(SecurityUtils.isHttpsUrl("https://192.168.1.1/api")).isFalse();
    }

    @Test
    public void isHttpsUrl_linkLocal_metadataEndpoint_rejected() {
        assertThat(SecurityUtils.isHttpsUrl("https://169.254.169.254/latest/meta-data/")).isFalse();
    }

    @Test
    public void isHttpsUrl_ipv6Loopback_rejected() {
        // URI with IPv6 literal: https://[::1]/api
        assertThat(SecurityUtils.isHttpsUrl("https://[::1]/api")).isFalse();
    }

    // ---- isHttpsUrl: dotless / decimal IPv4 SSRF bypass (regression) ----

    @Test
    public void isHttpsUrl_dotlessDecimalLoopback_rejected() {
        // 2130706433 == 127.0.0.1; Java's resolver collapses it to loopback, so it MUST be rejected
        // even though the host string contains no dot. Previously treated as a hostname → SSRF bypass.
        assertThat(SecurityUtils.isHttpsUrl("https://2130706433/api")).isFalse();
    }

    @Test
    public void isHttpsUrl_dotlessZeroAnyAddress_rejected() {
        // 0 == 0.0.0.0 (the wildcard / any-local address); must be rejected as internal.
        assertThat(SecurityUtils.isHttpsUrl("https://0/api")).isFalse();
    }

    @Test
    public void isHttpsUrl_hexLoopback_rejected() {
        // 0x7f000001 == 127.0.0.1 in hex notation; the 'x' previously made isIpLiteral skip the range
        // check, treating it as a hostname → SSRF bypass. It must now be classified as an IP literal
        // and rejected (range-checked, or fail-safe on UnknownHostException).
        assertThat(SecurityUtils.isHttpsUrl("https://0x7f000001/api")).isFalse();
        assertThat(SecurityUtils.isHttpsUrl("https://0x7f.0.0.1/api")).isFalse();
    }

    @Test
    public void isInternalHost_hexLoopback_returnsTrue() {
        assertThat(SecurityUtils.isInternalHost("0x7f000001")).isTrue();
    }

    @Test
    public void isInternalHost_dotlessDecimalLoopback_returnsTrue() {
        assertThat(SecurityUtils.isInternalHost("2130706433")).isTrue();
    }

    @Test
    public void isInternalHost_dotlessZeroAnyAddress_returnsTrue() {
        assertThat(SecurityUtils.isInternalHost("0")).isTrue();
        assertThat(SecurityUtils.isInternalHost("0.0.0.0")).isTrue();
    }

    @Test
    public void resolveHttpsBaseUrl_dotlessDecimalLoopback_throwsIllegalState() {
        assertThatThrownBy(() ->
                SecurityUtils.resolveHttpsBaseUrl("https://2130706433/api", "https://app.customgpt.ai/api/v1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("https");
    }

    @Test
    public void isHttpsUrl_publicIpLiteral_accepted() {
        // A public IP literal (not in any private range) should be accepted
        assertThat(SecurityUtils.isHttpsUrl("https://8.8.8.8/api")).isTrue();
    }

    @Test
    public void isHttpsUrl_publicHostname_accepted() {
        assertThat(SecurityUtils.isHttpsUrl("https://app.customgpt.ai/api/v1/")).isTrue();
    }

    @Test
    public void isHttpsUrl_productionDefaultUrl_accepted() {
        // The literal constant used in CustomGptConstants.DEFAULT_CUSTOM_GPT_API_BASE_URL
        assertThat(SecurityUtils.isHttpsUrl("https://app.customgpt.ai/api/v1")).isTrue();
    }

    // ---- dangerous URI schemes ----

    @Test
    public void isHttpsUrl_javascriptScheme_rejected() {
        assertThat(SecurityUtils.isHttpsUrl("javascript:alert(1)")).isFalse();
    }

    @Test
    public void isHttpsUrl_dataScheme_rejected() {
        assertThat(SecurityUtils.isHttpsUrl("data:text/html,<h1>hi</h1>")).isFalse();
    }

    @Test
    public void isHttpsUrl_ftpScheme_rejected() {
        assertThat(SecurityUtils.isHttpsUrl("ftp://app.customgpt.ai/file")).isFalse();
    }

    // ---- isMaskedPlaceholder: substring-not-equals guard ----

    @Test
    public void isMaskedPlaceholder_valueContainingPlaceholderAsSubstring_isFalse() {
        // A value that contains "********" as a substring but is not exactly the placeholder
        // must NOT be treated as the sentinel — the caller must still persist it.
        final String notExactly = SecurityUtils.SECRET_PLACEHOLDER + "extra";
        assertThat(SecurityUtils.isMaskedPlaceholder(notExactly)).isFalse();
    }

    @Test
    public void isMaskedPlaceholder_placeholderWithLeadingSpace_isFalse() {
        assertThat(SecurityUtils.isMaskedPlaceholder(" " + SecurityUtils.SECRET_PLACEHOLDER)).isFalse();
    }

    // ---- resolveHttpsBaseUrl: SSRF guard ----

    @Test
    public void resolveHttpsBaseUrl_privateIpBaseUrl_throwsIllegalState() {
        assertThatThrownBy(() ->
                SecurityUtils.resolveHttpsBaseUrl("https://192.168.1.99/api", "https://app.customgpt.ai/api/v1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("https");
    }

    @Test
    public void resolveHttpsBaseUrl_loopbackIpBaseUrl_throwsIllegalState() {
        assertThatThrownBy(() ->
                SecurityUtils.resolveHttpsBaseUrl("https://127.0.0.1/api", "https://app.customgpt.ai/api/v1"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void resolveHttpsBaseUrl_metadataEndpointBaseUrl_throwsIllegalState() {
        assertThatThrownBy(() ->
                SecurityUtils.resolveHttpsBaseUrl("https://169.254.169.254/latest", "https://app.customgpt.ai/api/v1"))
                .isInstanceOf(IllegalStateException.class);
    }
}
