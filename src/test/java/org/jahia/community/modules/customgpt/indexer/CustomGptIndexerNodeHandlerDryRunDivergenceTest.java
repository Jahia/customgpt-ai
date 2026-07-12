package org.jahia.community.modules.customgpt.indexer;

import java.util.Collections;
import okhttp3.OkHttpClient;
import org.jahia.api.Constants;
import org.jahia.community.modules.customgpt.CustomGptRequest;
import org.jahia.community.modules.customgpt.IndexRequest;
import org.jahia.community.modules.customgpt.service.Service;
import org.jahia.community.modules.customgpt.settings.Config;
import org.jahia.community.modules.customgpt.testutil.HttpsMockWebServerSupport;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRPropertyWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.jahia.services.content.JCRValueWrapper;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.jahia.services.content.decorator.JCRUserNode;
import org.jahia.services.sites.SitesSettings;
import org.jahia.services.usermanager.JahiaUser;
import org.jahia.services.usermanager.JahiaUserManagerService;
import org.junit.After;
import org.junit.Test;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * D2 — {@code dryRun} now correctly gates node-removal-triggered CustomGPT page deletions during normal
 * indexing, paired with D1's {@code purgeAllPages} fix.
 *
 * <p><b>Formerly a characterization test documenting a bug</b> (see the Stage 6/7 execution reports for the
 * original {@code handleNodeToReindex_dryRunTrue_stillDeletesQueuedPages_butSuppressesAddUpdate_documentsCurrentDivergence()}
 * test this class used to contain): {@code CustomGptIndexerNodeHandler.handleNodeToReindex()}'s
 * {@code customGptPageToRemove} deletion loop used to run unconditionally, ahead of the only
 * {@code isDryRun()} check in the file (inside {@code indexInSession()}, which only ever covered the
 * add/update path).
 *
 * <p><b>Fixed in Stage 7:</b> the deletion loop is now guarded by the same
 * {@code customGptIndexer.getCustomGptConfig().isDryRun()} check, so when {@code dryRun=true} both the
 * node-removal deletion path <em>and</em> the add/update path are suppressed - zero HTTP requests reach the
 * mock server for either sub-case.
 *
 * <p>Per the Tier-3 HTTPS/localhost note: the add/update sub-case's {@code apiBaseUrl} must clear
 * {@code SecurityUtils.resolveHttpsBaseUrl()}'s SSRF gate, so the MockWebServer is configured for HTTPS with
 * hostname {@code "localhost"} (see {@link HttpsMockWebServerSupport}).
 */
public class CustomGptIndexerNodeHandlerDryRunDivergenceTest {

    private MockedStatic<JahiaUserManagerService> jahiaUserManagerServiceStatic;
    private MockedStatic<JCRTemplate> jcrTemplateStatic;
    private HttpsMockWebServerSupport.HttpsFixture fixture;

    @After
    public void tearDown() throws Exception {
        if (jahiaUserManagerServiceStatic != null) {
            jahiaUserManagerServiceStatic.close();
        }
        if (jcrTemplateStatic != null) {
            jcrTemplateStatic.close();
        }
        if (fixture != null) {
            fixture.shutdown();
        }
    }

    @Test
    public void handleNodeToReindex_dryRunTrue_suppressesBothDeletionAndAddUpdate() throws Exception {
        fixture = HttpsMockWebServerSupport.start();
        // Deliberately zero responses enqueued: neither the queued-page-removal DELETE nor the add/update
        // POST/PUT should ever be issued while isDryRun()==true, so any unexpected request would fail with a
        // connection/response error instead of silently succeeding.

        final Config config = mock(Config.class);
        when(config.isDryRun()).thenReturn(true);
        when(config.getCustomGptProjectId()).thenReturn("proj1");
        when(config.getCustomGptToken()).thenReturn("tok");
        when(config.getCustomGptApiBaseUrl()).thenReturn(fixture.baseUrl());

        final Service service = mock(Service.class);
        final Indexer indexer = new Indexer(service, config);

        // ---- Arrange the node-removal side: a page already queued for deletion, no JCR path involved
        // (blank path so this does not also land in getNodePathsToRemove(), which is a separate, irrelevant
        // sub-case reprocessed through the add/update loop too). ----
        indexer.addNodeToDelete("customgpt-page-99", "");

        // ---- Arrange the add/update side: one different, still-existing, publishable node ----
        final String pagePath = "/sites/acme/home2";
        final JCRSessionWrapper systemSession = mock(JCRSessionWrapper.class);
        when(service.getSystemSession(isNull(), eq(Constants.LIVE_WORKSPACE), isNull())).thenReturn(systemSession);

        final JCRSiteNode siteNode = mock(JCRSiteNode.class);
        when(siteNode.getPropertyAsString("sitemapIndexURL")).thenReturn("https://www.example.com");

        final JCRNodeWrapper existingNode = mock(JCRNodeWrapper.class);
        when(existingNode.getPath()).thenReturn(pagePath);
        when(existingNode.isFile()).thenReturn(false);
        when(existingNode.getResolveSite()).thenReturn(siteNode);
        stubOneLanguage(siteNode, "en");

        when(systemSession.getNode(pagePath)).thenReturn(existingNode);
        when(service.getNodePathsToIndex(existingNode)).thenReturn(Collections.singleton(pagePath));
        // Same mock stands in for both "add/update path exists" checks; dryRun short-circuits before this
        // value's truth actually matters.
        when(systemSession.nodeExists(pagePath)).thenReturn(true);

        // Service.addIndexRequests(...) is mocked directly: skip the real IndexService/JCR content-type
        // matching machinery and just inject one IndexRequest, matching what production would produce for a
        // single-language publishable page.
        doAnswer(invocation -> {
            final JCRNodeWrapper node = invocation.getArgument(0);
            final String language = invocation.getArgument(1);
            @SuppressWarnings("unchecked")
            final java.util.Set<CustomGptRequest> requests = invocation.getArgument(2);
            requests.add(new IndexRequest(node, language));
            return null;
        }).when(service).addIndexRequests(eq(existingNode), any(), any());

        indexer.addNodePathToIndex(pagePath);

        // ---- JahiaUserManagerService / JCRTemplate static plumbing for the add/update path's index() call ----
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

        // ---- Act ---- (handleNodeToReindex is package-private; called directly, no reflection needed)
        // Both clients must trust the fixture's self-signed certificate, or a DELETE/POST call (if the fix
        // regressed) would fail the SSL handshake before ever reaching the mock server.
        final OkHttpClient customGptClient = fixture.trustingClientBuilder.build();
        final OkHttpClient jahiaClient = fixture.trustingClientBuilder.build();
        CustomGptIndexerNodeHandler.handleNodeToReindex(customGptClient, jahiaClient, indexer);

        // ---- Assert: the dry-run guard now suppresses BOTH the queued page-removal DELETE and the
        // add/update POST/PUT — zero requests should ever reach the mock server. ----
        assertThat(fixture.server.getRequestCount())
                .as("neither the queued-page-removal DELETE nor the add/update POST/PUT should reach the "
                        + "mock server when isDryRun()==true")
                .isEqualTo(0);
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
