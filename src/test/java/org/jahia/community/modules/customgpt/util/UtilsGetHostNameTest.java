package org.jahia.community.modules.customgpt.util;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.jahia.community.modules.customgpt.testutil.LogCapture;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for U2: {@link Utils#getHostName(JCRSiteNode)} — a site-level SSRF guard on the Jahia
 * rendering host, independent of F14's Basic-auth-only gating. Refuses to render the site at all (returns
 * {@code ""} and logs an error) when {@code sitemapIndexURL} resolves to a private/loopback/link-local
 * literal IP host.
 */
public class UtilsGetHostNameTest {

    private ListAppender<ILoggingEvent> appender;

    @Before
    public void setUp() {
        appender = LogCapture.attach(Utils.class);
    }

    @After
    public void tearDown() {
        LogCapture.detach(Utils.class, appender);
    }

    private static JCRSiteNode siteWithSitemapUrl(String sitemapIndexURL) {
        final JCRSiteNode siteNode = mock(JCRSiteNode.class);
        when(siteNode.getPropertyAsString("sitemapIndexURL")).thenReturn(sitemapIndexURL);
        when(siteNode.getPath()).thenReturn("/sites/acme");
        return siteNode;
    }

    @Test
    public void getHostName_internalIpHost_refusesAndReturnsEmpty() {
        final JCRSiteNode siteNode = siteWithSitemapUrl("http://192.168.1.10/sitemap.xml");

        final String hostName = Utils.getHostName(siteNode);

        assertThat(hostName).isEmpty();
        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.contains("Refusing to render site"));
    }

    @Test
    public void getHostName_loopbackHost_refusesAndReturnsEmpty() {
        final JCRSiteNode siteNode = siteWithSitemapUrl("https://127.0.0.1/sitemap.xml");

        final String hostName = Utils.getHostName(siteNode);

        assertThat(hostName).isEmpty();
    }

    @Test
    public void getHostName_linkLocalMetadataHost_refusesAndReturnsEmpty() {
        final JCRSiteNode siteNode = siteWithSitemapUrl("http://169.254.169.254/latest/meta-data/");

        final String hostName = Utils.getHostName(siteNode);

        assertThat(hostName).isEmpty();
    }

    @Test
    public void getHostName_publicHost_returnsExpectedHostName() {
        final JCRSiteNode siteNode = siteWithSitemapUrl("https://www.example.com/sitemap.xml");

        final String hostName = Utils.getHostName(siteNode);

        assertThat(hostName).isEqualTo("https://www.example.com");
    }

    @Test
    public void getHostName_malformedUrl_returnsEmptyWithoutThrowing() {
        final JCRSiteNode siteNode = siteWithSitemapUrl("not a url");

        final String hostName = Utils.getHostName(siteNode);

        assertThat(hostName).isEmpty();
    }
}
