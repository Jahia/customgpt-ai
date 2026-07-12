package org.jahia.community.modules.customgpt.graphql.extensions.query;

import java.util.LinkedHashSet;
import org.jahia.community.modules.customgpt.CustomGptConstants;
import org.jahia.community.modules.customgpt.graphql.extensions.models.GqlSettings;
import org.jahia.community.modules.customgpt.service.Service;
import org.jahia.community.modules.customgpt.settings.Config;
import org.jahia.community.modules.customgpt.util.SecurityUtils;
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
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminQueries#getSettings()}: the "not configured" default payload (U13) and the
 * secret-masking call sites for a fully configured instance (F12 residual — Stage 4 flagged this call site
 * as the one gap tied to a security invariant rather than just a feature).
 */
public class AdminQueriesTest {

    private MockedStatic<JCRSessionFactory> jcrSessionFactoryStatic;
    private MockedStatic<BundleUtils> bundleUtilsStatic;

    private void grantAdminPermission() throws Exception {
        final JCRSessionFactory factory = mock(JCRSessionFactory.class);
        final JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        final JCRNodeWrapper node = mock(JCRNodeWrapper.class);
        when(session.getNode(CustomGptConstants.PATH_DELIMITER)).thenReturn(node);
        when(node.hasPermission("customGptAdmin")).thenReturn(true);
        when(factory.getCurrentUserSession()).thenReturn(session);

        jcrSessionFactoryStatic = mockStatic(JCRSessionFactory.class);
        jcrSessionFactoryStatic.when(JCRSessionFactory::getInstance).thenReturn(factory);
    }

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
    public void getSettings_notConfigured_returnsHardcodedDefaults_neverNull() throws Exception {
        grantAdminPermission();
        final Config config = mock(Config.class);
        when(config.isConfigured()).thenReturn(false);

        bundleUtilsStatic = mockStatic(BundleUtils.class);
        bundleUtilsStatic.when(() -> BundleUtils.getOsgiService(Config.class, null)).thenReturn(config);

        final GqlSettings settings = new AdminQueries().getSettings();

        assertThat(settings.isDryRun()).isTrue();
        assertThat(settings.isScheduleJobASAP()).isFalse();
        assertThat(settings.getOperationsBatchSize()).isEqualTo(500);
        assertThat(settings.getRateLimitRequestsPerSecond()).isEqualTo(10);
        assertThat(settings.getApiBaseUrl()).isEqualTo(CustomGptConstants.DEFAULT_CUSTOM_GPT_API_BASE_URL);
        assertThat(settings.getContentIndexedMainResourceTypes()).isEmpty();
        assertThat(settings.getContentIndexedSubNodeTypes()).isEmpty();
        assertThat(settings.getContentIndexedFileExtensions()).isEmpty();
        assertThat(settings.getProjectId()).isEmpty();
        assertThat(settings.getProjectName()).isNull();
        assertThat(settings.getToken()).isEmpty();
        assertThat(settings.getJahiaUsername()).isEmpty();
        assertThat(settings.getJahiaPassword()).isEmpty();
        assertThat(settings.getJahiaServerCookieName()).isEmpty();
        assertThat(settings.getJahiaServerCookieValue()).isEmpty();
        assertThat(settings.getJahiaServerCookieDomain()).isEmpty();
    }

    @Test
    public void getSettings_nullConfigService_treatedSameAsNotConfigured() throws Exception {
        grantAdminPermission();
        bundleUtilsStatic = mockStatic(BundleUtils.class);
        bundleUtilsStatic.when(() -> BundleUtils.getOsgiService(Config.class, null)).thenReturn(null);

        final GqlSettings settings = new AdminQueries().getSettings();

        assertThat(settings.isDryRun()).isTrue();
        assertThat(settings.getApiBaseUrl()).isEqualTo(CustomGptConstants.DEFAULT_CUSTOM_GPT_API_BASE_URL);
    }

    @Test
    public void getSettings_configured_masksAllThreeSecretsButExposesUsernameAndProjectId() throws Exception {
        grantAdminPermission();
        final Config config = mock(Config.class);
        when(config.isConfigured()).thenReturn(true);
        when(config.getContentIndexedMainResources()).thenReturn(new LinkedHashSet<>());
        when(config.getContentIndexedSubNodes()).thenReturn(new LinkedHashSet<>());
        when(config.getIndexedFileExtensions()).thenReturn(new LinkedHashSet<>());
        when(config.getBulkOperationsBatchSize()).thenReturn(250);
        when(config.getCustomGptProjectId()).thenReturn("proj-1");
        when(config.getCustomGptToken()).thenReturn("sk-real-secret-token");
        when(config.getJahiaUsername()).thenReturn("root");
        when(config.getJahiaPassword()).thenReturn("real-jahia-password");
        when(config.getJahiaServerCookieName()).thenReturn("sess");
        when(config.getJahiaServerCookieValue()).thenReturn("real-cookie-value");
        when(config.getJahiaServerCookieDomain()).thenReturn("jahia.local");
        when(config.isDryRun()).thenReturn(false);
        when(config.isScheduleJobASAP()).thenReturn(true);
        when(config.getCustomGptApiBaseUrl()).thenReturn("https://app.customgpt.ai/api/v1");
        when(config.getRateLimitRequestsPerSecond()).thenReturn(7);

        bundleUtilsStatic = mockStatic(BundleUtils.class);
        bundleUtilsStatic.when(() -> BundleUtils.getOsgiService(Config.class, null)).thenReturn(config);
        // Service unavailable: getSettings() must tolerate this and simply resolve projectName as null.
        bundleUtilsStatic.when(() -> BundleUtils.getOsgiService(Service.class, null)).thenReturn(null);

        final GqlSettings settings = new AdminQueries().getSettings();

        // The 3 secrets must NEVER surface as their real value — only the placeholder.
        assertThat(settings.getToken()).isEqualTo(SecurityUtils.SECRET_PLACEHOLDER).doesNotContain("real-secret");
        assertThat(settings.getJahiaPassword()).isEqualTo(SecurityUtils.SECRET_PLACEHOLDER).doesNotContain("real-jahia-password");
        assertThat(settings.getJahiaServerCookieValue()).isEqualTo(SecurityUtils.SECRET_PLACEHOLDER).doesNotContain("real-cookie-value");
        // Non-secret fields pass through untouched.
        assertThat(settings.getJahiaUsername()).isEqualTo("root");
        assertThat(settings.getProjectId()).isEqualTo("proj-1");
        assertThat(settings.getProjectName()).isNull();
        assertThat(settings.getOperationsBatchSize()).isEqualTo(250);
        assertThat(settings.isDryRun()).isFalse();
        assertThat(settings.isScheduleJobASAP()).isTrue();
    }

    @Test
    public void getSettings_configuredWithUnsetSecrets_masksAsEmptyNotPlaceholder() throws Exception {
        grantAdminPermission();
        final Config config = mock(Config.class);
        when(config.isConfigured()).thenReturn(true);
        when(config.getContentIndexedMainResources()).thenReturn(new LinkedHashSet<>());
        when(config.getContentIndexedSubNodes()).thenReturn(new LinkedHashSet<>());
        when(config.getIndexedFileExtensions()).thenReturn(new LinkedHashSet<>());
        when(config.getBulkOperationsBatchSize()).thenReturn(500);
        when(config.getCustomGptProjectId()).thenReturn("");
        when(config.getCustomGptToken()).thenReturn("");
        when(config.getJahiaUsername()).thenReturn("");
        when(config.getJahiaPassword()).thenReturn("");
        when(config.getJahiaServerCookieName()).thenReturn("");
        when(config.getJahiaServerCookieValue()).thenReturn("");
        when(config.getJahiaServerCookieDomain()).thenReturn("");
        when(config.getCustomGptApiBaseUrl()).thenReturn(CustomGptConstants.DEFAULT_CUSTOM_GPT_API_BASE_URL);
        when(config.getRateLimitRequestsPerSecond()).thenReturn(10);

        bundleUtilsStatic = mockStatic(BundleUtils.class);
        bundleUtilsStatic.when(() -> BundleUtils.getOsgiService(Config.class, null)).thenReturn(config);
        bundleUtilsStatic.when(() -> BundleUtils.getOsgiService(Service.class, null)).thenReturn(null);

        final GqlSettings settings = new AdminQueries().getSettings();

        assertThat(settings.getToken()).isEmpty();
        assertThat(settings.getJahiaPassword()).isEmpty();
        assertThat(settings.getJahiaServerCookieValue()).isEmpty();
    }
}
