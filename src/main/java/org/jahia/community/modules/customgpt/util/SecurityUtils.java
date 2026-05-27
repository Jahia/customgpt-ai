package org.jahia.community.modules.customgpt.util;

import java.net.URI;
import java.net.URISyntaxException;
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
     * Validates that {@code url} is an absolute {@code https://} URL with a host. Used to gate the configurable
     * CustomGPT API base URL (which is sent together with the Bearer token) so the token cannot be exfiltrated over
     * cleartext or to a non-HTTP scheme.
     */
    public static boolean isHttpsUrl(String url) {
        if (StringUtils.isEmpty(url)) {
            return false;
        }
        try {
            final URI uri = new URI(url.trim());
            return SCHEME_HTTPS.equalsIgnoreCase(uri.getScheme()) && StringUtils.isNotEmpty(uri.getHost());
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
