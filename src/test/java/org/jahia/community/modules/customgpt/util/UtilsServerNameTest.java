package org.jahia.community.modules.customgpt.util;

import java.util.Dictionary;
import java.util.Hashtable;
import org.jahia.community.modules.customgpt.settings.Config;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the wiring between the configured server-name override and the host actually used for indexation.
 *
 * <p>{@link Utils#getHostName(JCRSiteNode, Config)} is the single point where the indexed URL's host is decided,
 * for both the indexation path and the URL repair pass. These tests pin the two halves of its contract: an override
 * replaces the {@code sitemapIndexURL} host outright, and the absence of one leaves the previous behaviour exactly
 * as it was.
 */
public class UtilsServerNameTest {

    private static final String NS = "org.jahia.community.modules.customgpt";
    private static final String SITEMAP_URL = "https://academy.jahia.com/sitemap.xml";

    @Test
    public void getHostName_usesThePerSiteOverrideInsteadOfTheSitemapHost() {
        // Arrange
        final JCRSiteNode siteNode = siteNode("academy");
        final Config config = configWith(NS + ".site.academy.serverName", "https://academypp.jahia.com");

        // Act
        final String hostName = Utils.getHostName(siteNode, config);

        // Assert: the sitemapIndexURL property is not even read when an override applies
        assertThat(hostName).isEqualTo("https://academypp.jahia.com");
        verify(siteNode, never()).getPropertyAsString("sitemapIndexURL");
    }

    @Test
    public void getHostName_usesTheGlobalOverrideForASiteThatHasNoneOfItsOwn() {
        final Config config = configWith(NS + ".serverName", "https://academypp.jahia.com");

        assertThat(Utils.getHostName(siteNode("digitall"), config)).isEqualTo("https://academypp.jahia.com");
    }

    @Test
    public void getHostName_fallsBackToTheSitemapHostWhenNothingIsConfigured() {
        // The override must be entirely inert for an install that does not set it.
        final Config config = configWith(null, null);

        assertThat(Utils.getHostName(siteNode("academy"), config)).isEqualTo("https://academy.jahia.com");
    }

    @Test
    public void getHostName_fallsBackToTheSitemapHostWhenTheOverrideIsRejected() {
        final Config config = configWith(NS + ".site.academy.serverName", "https://127.0.0.1");

        assertThat(Utils.getHostName(siteNode("academy"), config)).isEqualTo("https://academy.jahia.com");
    }

    @Test
    public void getHostName_fallsBackToTheSitemapHostWhenNoConfigIsSupplied() {
        assertThat(Utils.getHostName(siteNode("academy"), null)).isEqualTo("https://academy.jahia.com");
    }

    // ---- helpers ----

    private static JCRSiteNode siteNode(String siteKey) {
        final JCRSiteNode siteNode = mock(JCRSiteNode.class);
        when(siteNode.getSiteKey()).thenReturn(siteKey);
        when(siteNode.getPropertyAsString("sitemapIndexURL")).thenReturn(SITEMAP_URL);
        return siteNode;
    }

    private static Config configWith(String key, String value) {
        final Config config = new Config();
        final Dictionary<String, Object> props = new Hashtable<>();
        props.put(NS + ".apiBaseUrl", "https://app.customgpt.ai/api/v1");
        if (key != null) {
            props.put(key, value);
        }
        try {
            config.updated(props);
        } catch (Exception e) {
            // FrameworkService.sendEvent throws outside an OSGi container; parsing already ran.
        }
        return config;
    }
}
