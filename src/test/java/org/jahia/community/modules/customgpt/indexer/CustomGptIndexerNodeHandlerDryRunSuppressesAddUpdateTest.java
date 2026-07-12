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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * F7 — dry-run mode suppresses the add/update pathway. In isolation (companion to D2, which additionally
 * proves the *deletion* pathway is NOT suppressed in the same run): {@code indexInSession()}'s early return
 * at the {@code customGptIndexer.getCustomGptConfig().isDryRun()} check must fire before any
 * {@code POST /projects/{id}/sources} / {@code PUT .../metadata} call — the MockWebServer here has zero
 * responses enqueued, so any unexpected request would fail with a connection/response error, and the
 * explicit request-count assertion below independently confirms zero requests were made.
 */
public class CustomGptIndexerNodeHandlerDryRunSuppressesAddUpdateTest {

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
    public void handleNodeToReindex_dryRunTrue_suppressesAddUpdate_noHttpCallsMade() throws Exception {
        fixture = HttpsMockWebServerSupport.start();
        // Deliberately zero responses enqueued: indexInSession()'s dry-run short-circuit must fire before
        // either addPage()/POST or updatePageMedata()/PUT is ever issued.

        final Config config = mock(Config.class);
        when(config.isDryRun()).thenReturn(true);
        when(config.getCustomGptApiBaseUrl()).thenReturn(fixture.baseUrl());

        final Service service = mock(Service.class);
        final Indexer indexer = new Indexer(service, config);

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

        CustomGptIndexerNodeHandler.handleNodeToReindex(customGptClient, jahiaClient, indexer);

        assertThat(fixture.server.getRequestCount())
                .as("no addPage/updatePageMedata HTTP call should be made when isDryRun()==true")
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
