package org.jahia.community.modules.customgpt.graphql.extensions.query;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import java.util.Collection;
import javax.jcr.AccessDeniedException;
import javax.jcr.RepositoryException;
import org.jahia.community.modules.customgpt.CustomGptConstants;
import org.jahia.community.modules.customgpt.graphql.extensions.models.GqlSettings;
import org.jahia.community.modules.customgpt.graphql.extensions.models.GqlSiteListModel;
import org.jahia.community.modules.customgpt.service.Service;
import org.jahia.community.modules.customgpt.service.models.Site;
import org.jahia.community.modules.customgpt.settings.Config;
import org.jahia.community.modules.customgpt.util.SecurityUtils;
import org.jahia.modules.graphql.provider.dxm.DataFetchingException;
import org.jahia.osgi.BundleUtils;
import org.jahia.services.content.JCRSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GraphQL query resolver for the {@code admin.customGpt} namespace.
 * Provides access to indexed-site listing and configuration settings.
 * All methods check for the {@code customGptAdmin} permission on the root path before returning data,
 * matching the field-level {@code @GraphQLRequiresPermission("customGptAdmin")} annotation so the
 * fine-grained admin role works end-to-end.
 */
@GraphQLName("CustomGptAdminQueries")
@GraphQLDescription("List of indexed sites entry point")
public class AdminQueries {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdminQueries.class);
    private static final String CUSTOM_GPT_ADMIN = "customGptAdmin";
    private static final String ERR_PERMISSION = "Access denied or unable to verify permissions";
    private static final String ERR_SETTINGS = "Unable to load CustomGPT settings";

    @GraphQLField
    @GraphQLName("listSites")
    @GraphQLDescription("List sites configured for CustomGpt")
    public GqlSiteListModel getListSites() {
        try {
            checkAdminPermission(CustomGptConstants.PATH_DELIMITER, CUSTOM_GPT_ADMIN);
        } catch (RepositoryException e) {
            // Log the detail server-side; return a generic message so internal paths/JCR detail are not leaked.
            LOGGER.warn("Permission check failed for listSites", e);
            throw new DataFetchingException(ERR_PERMISSION);
        }
        final Collection<Site> indexedSites = BundleUtils.getOsgiService(Service.class, null).getIndexedSites().values();
        return new GqlSiteListModel(indexedSites);
    }

    @GraphQLField
    @GraphQLName("settings")
    @GraphQLDescription("Returns the current CustomGPT configuration settings")
    public GqlSettings getSettings() {
        try {
            checkAdminPermission(CustomGptConstants.PATH_DELIMITER, CUSTOM_GPT_ADMIN);
        } catch (RepositoryException e) {
            LOGGER.warn("Permission check failed for settings", e);
            throw new DataFetchingException(ERR_PERMISSION);
        }
        final Config config = BundleUtils.getOsgiService(Config.class, null);
        if (config == null || !config.isConfigured()) {
            return GqlSettings.builder()
                    .contentIndexedMainResourceTypes("").contentIndexedSubNodeTypes("").contentIndexedFileExtensions("")
                    .fileMappedNodetypes("").operationsBatchSize(500).projectId("").projectName(null).token("")
                    .jahiaApiToken("").jahiaGraphqlEndpoint("").jahiaServerCookieName("").jahiaServerCookieValue("")
                    .jahiaServerCookieDomain("").userAgent("").serverName("").siteServerNames("")
                    .dryRun(true).scheduleJobASAP(false)
                    .apiBaseUrl(CustomGptConstants.DEFAULT_CUSTOM_GPT_API_BASE_URL)
                    .rateLimitRequestsPerSecond(10).build();
        }
        try {
            final Service service = BundleUtils.getOsgiService(Service.class, null);
            final String projectName = service != null ? service.getProjectName() : null;
            return GqlSettings.builder()
                    .contentIndexedMainResourceTypes(String.join(",", config.getContentIndexedMainResources()))
                    .contentIndexedSubNodeTypes(String.join(",", config.getContentIndexedSubNodes()))
                    .contentIndexedFileExtensions(String.join(",", config.getIndexedFileExtensions()))
                    .fileMappedNodetypes("")
                    .operationsBatchSize(config.getBulkOperationsBatchSize())
                    .projectId(config.getCustomGptProjectId())
                    .projectName(projectName)
                    // Secrets are write-only: never echo the stored value back to the client, only whether one is set.
                    .token(SecurityUtils.maskSecretForDisplay(config.getCustomGptToken()))
                    .jahiaApiToken(SecurityUtils.maskSecretForDisplay(config.getJahiaApiToken()))
                    .jahiaGraphqlEndpoint(config.getJahiaGraphqlEndpoint())
                    .jahiaServerCookieName(config.getJahiaServerCookieName())
                    .jahiaServerCookieValue(config.getJahiaServerCookieValue())
                    .jahiaServerCookieDomain(config.getJahiaServerCookieDomain())
                    .userAgent(config.getUserAgent())
                    .serverName(config.getServerName(null))
                    .siteServerNames(formatSiteServerNames(config.getSiteServerNames()))
                    .dryRun(config.isDryRun())
                    .scheduleJobASAP(config.isScheduleJobASAP())
                    .apiBaseUrl(config.getCustomGptApiBaseUrl())
                    .rateLimitRequestsPerSecond(config.getRateLimitRequestsPerSecond())
                    .build();
        } catch (org.jahia.community.modules.customgpt.settings.NotConfiguredException e) {
            LOGGER.warn("Unable to read CustomGPT settings", e);
            throw new DataFetchingException(ERR_SETTINGS);
        }
    }

    private void checkAdminPermission(String path, String permission) throws RepositoryException {
        if (!JCRSessionFactory.getInstance().getCurrentUserSession().getNode(path).hasPermission(permission)) {
            throw new AccessDeniedException(permission);
        }
    }

    /**
     * Renders the per-site overrides as one {@code siteKey=serverName} per line.
     *
     * <p>A flat string rather than a GraphQL list type on purpose: the settings panel edits these as rows, but
     * sending them as text keeps the schema free of a new input type - and a bad input type registration takes
     * down the whole DXGraphQLProvider schema, not just this field.
     */
    private static String formatSiteServerNames(java.util.Map<String, String> bySite) {
        if (bySite == null || bySite.isEmpty()) {
            return "";
        }
        return bySite.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(java.util.stream.Collectors.joining("\n"));
    }
}
