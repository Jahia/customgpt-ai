package org.jahia.community.modules.customgpt.util;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import org.apache.commons.lang.StringUtils;

/**
 * Pure (no JCR / no OSGi) security helpers shared by the GraphQL layer and the indexer.
 *
 * <p>Two concerns are handled here:
 * <ul>
 *   <li><b>Write-only secrets.</b> Stored secrets (the CustomGPT token, the Jahia rendering password and the
 *       server-cookie value) must never be echoed back to a client. {@link #maskSecretForDisplay(String)} replaces
 *       a stored value with {@link #SECRET_PLACEHOLDER}, and {@link #isUnchangedSecret(String)} lets the save path
 *       recognise that sentinel (or a blank) and leave the stored value untouched.</li>
 *   <li><b>Transport safety.</b> {@link #isHttpsUrl(String)} gates anything that carries a credential (the
 *       configurable CustomGPT {@code apiBaseUrl} that travels with the Bearer token, and the Jahia page URL that
 *       travels with Basic auth) so secrets never leave over cleartext or a non-URL scheme.</li>
 * </ul>
 */
public final class SecurityUtils {

    /**
     * Sentinel returned in place of a stored secret. Fixed-length, ASCII, and deliberately not a plausible real
     * secret so the save path can detect "the admin did not change this field" without ever seeing the real value.
     */
    public static final String SECRET_PLACEHOLDER = "********";

    private static final String SCHEME_HTTPS = "https";

    private SecurityUtils() {
        throw new IllegalStateException("Utility class");
    }

    /**
     * Returns the placeholder when a secret is set, or an empty string when it is not. Never returns the real value,
     * so a stored secret cannot be read back through the API.
     */
    public static String maskSecretForDisplay(String storedSecret) {
        return StringUtils.isEmpty(storedSecret) ? "" : SECRET_PLACEHOLDER;
    }

    /**
     * Returns {@code true} when an incoming secret value is the masking placeholder echoed back unchanged by the UI.
     * Such a value must never be persisted (it would overwrite the real secret with {@code ********}); callers skip
     * writing the property so the stored secret is preserved. An explicit empty string is NOT a placeholder — it
     * still means "clear this secret".
     */
    public static boolean isMaskedPlaceholder(String incoming) {
        return SECRET_PLACEHOLDER.equals(incoming);
    }

    /**
     * Validates that {@code url} is an absolute {@code https://} URL with a host, and that the host is not a literal
     * private/loopback/link-local/unique-local IP address. Used to gate the configurable CustomGPT API base URL
     * (which is sent together with the Bearer token) and the Jahia rendering URL (which carries Basic auth) so the
     * credential cannot be exfiltrated over cleartext, to a non-HTTP scheme, or to an internal SSRF target.
     *
     * <p>Hostnames are accepted as-is — no DNS resolution is performed (resolving an arbitrary attacker-supplied
     * hostname would itself be an SSRF/DoS vector). Only literal IP-address hosts are checked against the
     * private/internal ranges; see {@link #isInternalHost(String)}.
     */
    public static boolean isHttpsUrl(String url) {
        if (StringUtils.isEmpty(url)) {
            return false;
        }
        try {
            final URI uri = new URI(url.trim());
            final String host = uri.getHost();
            return SCHEME_HTTPS.equalsIgnoreCase(uri.getScheme()) && StringUtils.isNotEmpty(host) && !isInternalHost(host);
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /**
     * Returns {@code true} when {@code host} is a <em>literal</em> IP address that falls in a private, loopback,
     * link-local or unique-local range, which an outbound credential-bearing request must never target (SSRF).
     * Covered ranges: {@code 10.0.0.0/8}, {@code 127.0.0.0/8}, {@code 169.254.0.0/16}, {@code 172.16.0.0/12},
     * {@code 192.168.0.0/16}, {@code ::1} and {@code fc00::/7}.
     *
     * <p>Non-IP hostnames (e.g. {@code app.customgpt.ai}) are NOT resolved and return {@code false} — only a value
     * that already parses as an IP literal is range-checked, so this method never performs DNS lookups.
     *
     * <p>This is package-friendly (public + static) so it can be unit-tested directly.
     */
    public static boolean isInternalHost(String host) {
        if (StringUtils.isEmpty(host)) {
            return false;
        }
        String candidate = host.trim();
        // Strip the brackets used around IPv6 literals in URLs, e.g. "[::1]".
        if (candidate.startsWith("[") && candidate.endsWith("]")) {
            candidate = candidate.substring(1, candidate.length() - 1);
        }
        if (!isIpLiteral(candidate)) {
            // A hostname, not an IP literal: do not resolve it (DNS lookups are themselves an SSRF/DoS risk).
            return false;
        }
        try {
            final InetAddress address = InetAddress.getByName(candidate);
            return address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()
                    || address.isAnyLocalAddress()
                    || isUniqueLocalIpv6(address);
        } catch (UnknownHostException e) {
            // Should not happen for an IP literal; treat as internal to fail safe.
            return true;
        }
    }

    /**
     * Returns {@code true} only when {@code value} is a numeric IP literal that {@link InetAddress#getByName(String)}
     * resolves locally without a DNS round-trip: an IPv6 literal (contains a colon), a dotted IPv4 literal, OR a
     * dotless numeric form (decimal/integer, e.g. {@code 2130706433} for {@code 127.0.0.1} or {@code 0} for
     * {@code 0.0.0.0}). Java's resolver — and therefore OkHttp — collapses these dotless forms to real loopback/any
     * addresses, so they MUST be range-checked rather than treated as opaque hostnames (SSRF bypass). An all-digits
     * string can never be a registrable DNS hostname, so accepting it here introduces no DNS lookup of a real host.
     */
    private static boolean isIpLiteral(String value) {
        // IPv6 literals contain a colon.
        if (value.indexOf(':') >= 0) {
            return true;
        }
        // Hex IPv4 forms (e.g. 0x7f000001 or 0x7f.0.0.1): every numeric segment is prefixed with "0x"/"0X".
        // Real hostnames never start with "0x", so classifying these as IP literals routes them through the
        // range check (or InetAddress's UnknownHostException -> fail-safe) and closes the hex SSRF bypass
        // without resolving any genuine hostname.
        final String lower = value.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("0x") || lower.contains(".0x")) {
            return true;
        }
        boolean hasDigit = false;
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c == '.') {
                continue;
            }
            if (c < '0' || c > '9') {
                // A real hostname (contains a non-digit, non-dot character) — not an IP literal.
                return false;
            }
            hasDigit = true;
        }
        // All-digits (dotless decimal/integer) or dotted-numeric: a numeric IP literal.
        return hasDigit;
    }

    /** Returns {@code true} for IPv6 unique-local addresses ({@code fc00::/7}), which Java does not flag directly. */
    private static boolean isUniqueLocalIpv6(InetAddress address) {
        final byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }

    /**
     * Resolves an outbound API base URL that carries a credential: applies {@code defaultUrl} when {@code configured}
     * is blank, strips a single trailing slash, and asserts the result is an {@code https://} URL. Centralising this
     * (used by both the synchronous service calls and the indexer) guarantees the Bearer token is never sent over a
     * cleartext or non-HTTP base URL, even when the value is set directly in the {@code .cfg} (bypassing the
     * {@code saveSettings} UI gate).
     *
     * @throws IllegalStateException when the resolved base URL is not a valid {@code https://} URL
     */
    public static String resolveHttpsBaseUrl(String configured, String defaultUrl) {
        String baseUrl = StringUtils.isEmpty(configured) ? defaultUrl : configured;
        if (baseUrl != null && baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        if (!isHttpsUrl(baseUrl)) {
            throw new IllegalStateException("CustomGPT apiBaseUrl must be a valid https:// URL");
        }
        return baseUrl;
    }

    /**
     * Removes CR/LF (and other ISO control) characters from a value before it is written to a log line, so that
     * attacker- or config-controlled strings cannot forge additional log records (log injection / log forging).
     * Returns {@code null} unchanged so callers can still distinguish a missing value.
     */
    public static String sanitizeForLog(String value) {
        if (value == null) {
            return null;
        }
        final StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            sb.append(Character.isISOControl(c) ? '_' : c);
        }
        return sb.toString();
    }
}
