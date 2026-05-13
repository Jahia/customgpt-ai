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
import org.jahia.modules.graphql.provider.dxm.DataFetchingException;
import org.jahia.osgi.BundleUtils;
import org.jahia.services.content.JCRSessionFactory;

/**
 * GraphQL query resolver for the {@code admin.customGpt} namespace.
 * Provides access to indexed-site listing and configuration settings.
 * All methods check for the {@code admin} permission on the root path before returning data.
 */
@GraphQLName("CustomGptAdminQueries")
@GraphQLDescription("List of indexed sites entry point")
public class AdminQueries {

    private static final String ADMIN = "admin";

    @GraphQLField
    @GraphQLName("listSites")
    @GraphQLDescription("List sites configured for CustomGpt")
    public GqlSiteListModel getListSites() {
        try {
            checkAdminPermission(CustomGptConstants.PATH_DELIMITER, ADMIN);
        } catch (RepositoryException e) {
            throw new DataFetchingException(e);
        }
        final Collection<Site> indexedSites = BundleUtils.getOsgiService(Service.class, null).getIndexedSites().values();
        return new GqlSiteListModel(indexedSites);
    }

    @GraphQLField
    @GraphQLName("settings")
    @GraphQLDescription("Returns the current CustomGPT configuration settings")
    public GqlSettings getSettings() {
        try {
            checkAdminPermission(CustomGptConstants.PATH_DELIMITER, ADMIN);
        } catch (RepositoryException e) {
            throw new DataFetchingException(e);
        }
        final Config config = BundleUtils.getOsgiService(Config.class, null);
        if (config == null || !config.isConfigured()) {
            return GqlSettings.builder()
                    .contentIndexedMainResourceTypes("").contentIndexedSubNodeTypes("").contentIndexedFileExtensions("")
                    .fileMappedNodetypes("").operationsBatchSize(500).projectId("").projectName(null).token("")
                    .jahiaUsername("").jahiaPassword("").jahiaServerCookieName("").jahiaServerCookieValue("")
                    .jahiaServerCookieDomain("").dryRun(true).scheduleJobASAP(false)
                    .apiBaseUrl(CustomGptConstants.DEFAULT_CUSTOM_GPT_API_BASE_URL).build();
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
                    .token(config.getCustomGptToken())
                    .jahiaUsername(config.getJahiaUsername())
                    .jahiaPassword(config.getJahiaPassword())
                    .jahiaServerCookieName(config.getJahiaServerCookieName())
                    .jahiaServerCookieValue(config.getJahiaServerCookieValue())
                    .jahiaServerCookieDomain(config.getJahiaServerCookieDomain())
                    .dryRun(config.isDryRun())
                    .scheduleJobASAP(config.isScheduleJobASAP())
                    .apiBaseUrl(config.getCustomGptApiBaseUrl())
                    .build();
        } catch (org.jahia.community.modules.customgpt.settings.NotConfiguredException e) {
            throw new DataFetchingException(e);
        }
    }

    private void checkAdminPermission(String path, String permission) throws RepositoryException {
        if (!JCRSessionFactory.getInstance().getCurrentUserSession().getNode(path).hasPermission(permission)) {
            throw new AccessDeniedException(permission);
        }
    }
}
