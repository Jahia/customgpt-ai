package org.jahia.community.modules.customgpt.indexer;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.Collections;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import org.jahia.api.Constants;
import org.jahia.community.modules.customgpt.CustomGptRequest;
import org.jahia.community.modules.customgpt.IndexRequest;
import org.jahia.community.modules.customgpt.graphql.extensions.models.GqlSiteListModel;
import org.jahia.community.modules.customgpt.service.Service;
import org.jahia.community.modules.customgpt.settings.Config;
import org.jahia.community.modules.customgpt.testutil.HttpsMockWebServerSupport;
import org.jahia.community.modules.customgpt.testutil.LogCapture;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRPropertyWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.jahia.services.content.JCRValueWrapper;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.jahia.services.content.decorator.JCRUserNode;
import org.jahia.services.seo.urlrewrite.UrlRewriteService;
import org.jahia.services.sites.SitesSettings;
import org.jahia.services.usermanager.JahiaUser;
import org.jahia.services.usermanager.JahiaUserManagerService;
import org.jahia.osgi.BundleUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * D4 — no retry/alerting for CustomGPT 5xx, timeouts, or invalid-token errors beyond the fixed
 * Jahia-render retry. {@code uploadAndUpdateMetadata()}'s {@code addPage()} failure throws an
 * {@link java.io.IOException} that propagates up and is caught only by {@code indexInSession()}'s catch
 * block, which simply logs {@code "Issue:"} and returns — no retry, no admin-visible failure state.
 */
public class CustomGptIndexerNodeHandlerNoRetryOnCustomGptErrorTest {

    private MockedStatic<JahiaUserManagerService> jahiaUserManagerServiceStatic;
    private MockedStatic<JCRTemplate> jcrTemplateStatic;
    private MockedStatic<BundleUtils> bundleUtilsStatic;
    private HttpsMockWebServerSupport.HttpsFixture fixture;
    private ListAppender<ILoggingEvent> appender;

    @Before
    public void setUp() {
        appender = LogCapture.attach(CustomGptIndexerNodeHandler.class);
    }

    @After
    public void tearDown() throws Exception {
        LogCapture.detach(CustomGptIndexerNodeHandler.class, appender);
        if (jahiaUserManagerServiceStatic != null) {
            jahiaUserManagerServiceStatic.close();
        }
        if (jcrTemplateStatic != null) {
            jcrTemplateStatic.close();
        }
        if (bundleUtilsStatic != null) {
            bundleUtilsStatic.close();
        }
        if (fixture != null) {
            fixture.shutdown();
        }
    }

    @Test
    public void handleNodeToReindex_customGptReturns500_oneAttemptOnly_loggedNotThrown() throws Exception {
        fixture = HttpsMockWebServerSupport.start();
        // First response: the Jahia page-render GET (indexJahiaPage), which must succeed so the flow
        // actually reaches uploadAndUpdateMetadata()'s addPage() call. Second response: the CustomGPT-side
        // POST /projects/{id}/sources returning 500.
        fixture.server.enqueue(new MockResponse().setResponseCode(200).setBody("<html>ok</html>"));
        fixture.server.enqueue(new MockResponse().setResponseCode(500).setBody("{\"error\":\"internal\"}"));

        runAddUpdateScenario(false, fixture.baseUrl());

        assertThat(fixture.server.getRequestCount())
                .as("exactly one Jahia-render GET plus exactly one POST attempt - no retry on a "
                        + "CustomGPT-side 5xx")
                .isEqualTo(2);
        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.contains("Issue:"));
        assertThat(appender.list)
                .anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR
                        && e.getFormattedMessage().contains("Issue:"));
    }

    @Test
    public void handleNodeToReindex_connectionRefused_oneAttemptOnly_loggedNotThrown() throws Exception {
        fixture = HttpsMockWebServerSupport.start();
        // The Jahia-render GET must still succeed (via the real fixture server); only the CustomGPT-side
        // apiBaseUrl below is deliberately unreachable, giving a deterministic connection-refused failure
        // (a mid-stream MockWebServer disconnect interacts unpredictably with OkHttp's transparent
        // retry-on-connection-failure and Java's own retryable-IOException handling).
        fixture.server.enqueue(new MockResponse().setResponseCode(200).setBody("<html>ok</html>"));
        final int unreachablePort = findClosedLocalPort();

        runAddUpdateScenario(false, "https://localhost:" + unreachablePort);

        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.contains("Issue:"));
    }

    /** Opens then immediately closes a local server socket, guaranteeing the returned port refuses connections. */
    private static int findClosedLocalPort() throws java.io.IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    public void indexationStatus_hasNoFailedValue_crossReferencesU7() {
        assertThat(GqlSiteListModel.IndexedSite.IndexationStatus.values())
                .extracting(Enum::name)
                .containsExactly("SCHEDULED", "STARTED", "COMPLETED");
    }

    private void runAddUpdateScenario(boolean dryRun, String customGptApiBaseUrl) throws Exception {
        final Config config = mock(Config.class);
        when(config.isDryRun()).thenReturn(dryRun);
        when(config.getCustomGptProjectId()).thenReturn("proj1");
        when(config.getCustomGptToken()).thenReturn("tok");
        when(config.getCustomGptApiBaseUrl()).thenReturn(customGptApiBaseUrl);

        final Service service = mock(Service.class);
        final Indexer indexer = new Indexer(service, config);

        final String pagePath = "/sites/acme/home2";
        final JCRSessionWrapper systemSession = mock(JCRSessionWrapper.class);
        when(service.getSystemSession(isNull(), eq(Constants.LIVE_WORKSPACE), isNull())).thenReturn(systemSession);

        final JCRSiteNode siteNode = mock(JCRSiteNode.class);
        // Point the Jahia-render host at the SAME mock server (not a real external host) so the
        // indexJahiaPage() GET request is actually observable/scriptable here too.
        when(siteNode.getPropertyAsString("sitemapIndexURL")).thenReturn(fixture.baseUrl() + "/sitemap.xml");

        final JCRNodeWrapper existingNode = mock(JCRNodeWrapper.class);
        when(existingNode.getPath()).thenReturn(pagePath);
        when(existingNode.isFile()).thenReturn(false);
        when(existingNode.getResolveSite()).thenReturn(siteNode);
        when(existingNode.getUrl()).thenReturn("/sites/acme/home2.html");
        stubOneLanguage(siteNode, "en");

        // Utils.encode() -> encodeLink() resolves UrlRewriteService via BundleUtils; stub it as an identity
        // pass-through so the URL-building step ahead of indexJahiaPage()/addPage() does not NPE.
        final UrlRewriteService urlRewriteService = mock(UrlRewriteService.class);
        when(urlRewriteService.rewriteOutbound(any(), any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        bundleUtilsStatic = mockStatic(BundleUtils.class);
        bundleUtilsStatic.when(() -> BundleUtils.getOsgiService(UrlRewriteService.class, null)).thenReturn(urlRewriteService);

        when(systemSession.getNode(pagePath)).thenReturn(existingNode);
        when(systemSession.nodeExists(pagePath)).thenReturn(true);
        when(service.getNodePathsToIndex(existingNode)).thenReturn(Collections.singleton(pagePath));

        doAnswer(invocation -> {
            final JCRNodeWrapper node = invocation.getArgument(0);
            final String language = invocation.getArgument(1);
            @SuppressWarnings("unchecked")
            final java.util.Set<CustomGptRequest> requests = invocation.getArgument(2);
            requests.add(new IndexRequest(node, language));
            return null;
        }).when(service).addIndexRequests(eq(existingNode), any(), any());

        indexer.addNodePathToIndex(pagePath);

        final JCRUserNode rootUserNode = mock(JCRUserNode.class);
        final JahiaUser rootUser = mock(JahiaUser.class);
        when(rootUserNode.getJahiaUser()).thenReturn(rootUser);
        final JahiaUserManagerService userManagerService = mock(JahiaUserManagerService.class);
        when(userManagerService.lookupRootUser()).thenReturn(rootUserNode);
        jahiaUserManagerServiceStatic = mockStatic(JahiaUserManagerService.class);
        jahiaUserManagerServiceStatic.when(JahiaUserManagerService::getInstance).thenReturn(userManagerService);

        final JCRTemplate template = mock(JCRTemplate.class);
        when(template.doExecuteWithSystemSessionAsUser(any(), any(), any(), any())).thenAnswer(invocation -> {
            final org.jahia.services.content.JCRCallback<?> callback = invocation.getArgument(3);
            return callback.doInJCR(systemSession);
        });
        jcrTemplateStatic = mockStatic(JCRTemplate.class);
        jcrTemplateStatic.when(JCRTemplate::getInstance).thenReturn(template);

        final OkHttpClient customGptClient = fixture.trustingClientBuilder.build();
        final OkHttpClient jahiaClient = fixture.trustingClientBuilder.build();

        // Must not throw to the caller - the failure is caught and logged inside indexInSession().
        CustomGptIndexerNodeHandler.handleNodeToReindex(customGptClient, jahiaClient, indexer);
    }

    private static void stubOneLanguage(JCRSiteNode siteNode, String language) throws Exception {
        final JCRPropertyWrapper languagesProperty = mock(JCRPropertyWrapper.class);
        final JCRValueWrapper value = mock(JCRValueWrapper.class);
        when(value.getString()).thenReturn(language);
        when(languagesProperty.getValues()).thenReturn(new JCRValueWrapper[]{value});
        when(siteNode.hasProperty(SitesSettings.LANGUAGES)).thenReturn(true);
        when(siteNode.getProperty(SitesSettings.LANGUAGES)).thenReturn(languagesProperty);
        when(siteNode.hasProperty(SitesSettings.INACTIVE_LIVE_LANGUAGES)).thenReturn(false);
    }
}
