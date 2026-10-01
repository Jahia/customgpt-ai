package org.jahia.community.modules.customgpt.indexer;

import okhttp3.HttpUrl;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the origin check that decides whether a redirect may be followed.
 *
 * <p>The Jahia render client is built {@code .followRedirects(false)} on purpose: the request carries Basic
 * credentials, and OkHttp would replay them to wherever a redirect pointed. That control is kept - redirects are
 * followed here explicitly, and only when the destination is the SAME ORIGIN, so credentials can never leave the
 * host they were issued for.
 *
 * <p>Why it has to be followed at all: Jahia answers the raw {@code .html} path with a 301 to its canonical form,
 * so a first publish failed outright with "Impossible to retrieve content from ... (HTTP 301)" and the node was
 * never indexed.
 */
public class SameOriginRedirectTest {

    private static boolean sameOrigin(String from, String to) {
        return CustomGptIndexerNodeHandler.isSameOrigin(HttpUrl.get(from), HttpUrl.get(to));
    }

    @Test
    public void thePlainCaseIsTheOneThatBrokeAFirstPublish() {
        assertThat(sameOrigin(
                "https://academy.jahia.com/contents/knowledge-base/2026/contributing-to-the-page-head.html",
                "https://academy.jahia.com/contents/knowledge-base/2026/contributing-to-the-page-head")).isTrue();
    }

    @Test
    public void aDifferentHostIsRefused() {
        // The reason the client has followRedirects(false): this request carries Basic credentials.
        assertThat(sameOrigin("https://academy.jahia.com/x", "https://evil.example/x")).isFalse();
        assertThat(sameOrigin("https://academy.jahia.com/x", "https://www.jahia.com/x")).isFalse();
    }

    @Test
    public void aSubdomainIsADifferentHost() {
        assertThat(sameOrigin("https://academy.jahia.com/x", "https://cdn.academy.jahia.com/x")).isFalse();
    }

    @Test
    public void downgradingToHttpIsRefused() {
        // Following this would put the credentials on the wire in cleartext.
        assertThat(sameOrigin("https://academy.jahia.com/x", "http://academy.jahia.com/x")).isFalse();
    }

    @Test
    public void aDifferentPortIsADifferentOrigin() {
        assertThat(sameOrigin("https://academy.jahia.com/x", "https://academy.jahia.com:8443/x")).isFalse();
    }

    @Test
    public void theDefaultPortMatchesTheExplicitDefaultPort() {
        assertThat(sameOrigin("https://academy.jahia.com/x", "https://academy.jahia.com:443/y")).isTrue();
    }

    @Test
    public void aRedirectIsNotTreatedAsTransient() {
        // A 301 is a routing fact, not a blip. Retrying it three times with 500ms sleeps wasted a second per
        // page and never changed the answer.
        assertThat(CustomGptIndexerNodeHandler.isRedirect(301)).isTrue();
        assertThat(CustomGptIndexerNodeHandler.isRedirect(302)).isTrue();
        assertThat(CustomGptIndexerNodeHandler.isRedirect(307)).isTrue();
        assertThat(CustomGptIndexerNodeHandler.isRedirect(308)).isTrue();
        assertThat(CustomGptIndexerNodeHandler.isRedirect(200)).isFalse();
        assertThat(CustomGptIndexerNodeHandler.isRedirect(404)).isFalse();
        assertThat(CustomGptIndexerNodeHandler.isRedirect(500)).isFalse();
    }
}
