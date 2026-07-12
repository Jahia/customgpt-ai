package org.jahia.community.modules.customgpt.graphql.extensions.models;

import org.jahia.community.modules.customgpt.CustomGptConstants;
import org.jahia.community.modules.customgpt.service.Service;
import org.jahia.osgi.BundleUtils;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.junit.After;
import org.junit.Test;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F1 (residual) — {@code startIndex(siteKeys: null, ...)}, the "no siteKeys ⇒ reindex all sites" branch.
 * Every existing Cypress E2E test always passes an explicit {@code siteKeys} list (see 02-indexing.cy.ts),
 * so this branch (`GqlCustomGptAdminMutationResult.getJobDetailList`, {@code siteKeys == null} → calls
 * {@code Service.reIndexUsingJob()} with no site filter) was never exercised by any test.
 *
 * <p>Per the Stage 4 gap list's own recommendation, this is genuinely cheaper as a direct JUnit test than a
 * Cypress spec (a mockable seam already exists via {@code BundleUtils.getOsgiService(Service.class, null)}),
 * so it was moved out of Tier 6 (Cypress) into this JUnit test rather than duplicated as an E2E spec.
 */
public class GqlCustomGptAdminMutationResultStartIndexTest {

    private MockedStatic<JCRSessionFactory> jcrSessionFactoryStatic;
    private MockedStatic<BundleUtils> bundleUtilsStatic;

    @After
    public void tearDown() {
        if (jcrSessionFactoryStatic != null) {
            jcrSessionFactoryStatic.close();
        }
        if (bundleUtilsStatic != null) {
            bundleUtilsStatic.close();
        }
    }

    @Test
    public void startIndex_nullSiteKeys_reindexesAllSites_returnsEmptyJobList() throws Exception {
        final JCRSessionFactory factory = mock(JCRSessionFactory.class);
        final JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        final JCRNodeWrapper node = mock(JCRNodeWrapper.class);
        when(session.getNode(CustomGptConstants.PATH_DELIMITER)).thenReturn(node);
        when(node.hasPermission("customGptAdmin")).thenReturn(true);
        when(factory.getCurrentUserSession()).thenReturn(session);
        jcrSessionFactoryStatic = mockStatic(JCRSessionFactory.class);
        jcrSessionFactoryStatic.when(JCRSessionFactory::getInstance).thenReturn(factory);

        final Service service = mock(Service.class);
        bundleUtilsStatic = mockStatic(BundleUtils.class);
        bundleUtilsStatic.when(() -> BundleUtils.getOsgiService(Service.class, null)).thenReturn(service);

        final GqlIndexMutationResult result = new GqlCustomGptAdminMutationResult().startIndex(null, false);

        verify(service, times(1)).reIndexUsingJob();
        verify(service, org.mockito.Mockito.never()).reIndexUsingJob(org.mockito.ArgumentMatchers.anyString());
        verify(service, org.mockito.Mockito.never()).reIndexUsingJob(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(service, org.mockito.Mockito.never()).getIndexedSites();
        assertThat(result.getJobs()).isNotNull().isEmpty();
    }
}
