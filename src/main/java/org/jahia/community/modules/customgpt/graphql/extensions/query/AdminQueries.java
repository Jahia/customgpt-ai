package org.jahia.community.modules.customgpt.graphql.extensions.query;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import java.util.Collection;
import javax.jcr.AccessDeniedException;
import javax.jcr.RepositoryException;
import java.util.Collections;
import org.jahia.community.modules.customgpt.CustomGptConstants;
import org.jahia.community.modules.customgpt.graphql.extensions.models.GqlSettings;
import org.jahia.community.modules.customgpt.graphql.extensions.models.GqlSiteListModel;
import org.jahia.community.modules.customgpt.service.Service;
import org.jahia.community.modules.customgpt.service.models.Site;
import org.jahia.community.modules.customgpt.settings.Config;
import org.jahia.modules.graphql.provider.dxm.DataFetchingException;
import org.jahia.osgi.BundleUtils;
import org.jahia.services.content.JCRSessionFactory;

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
            return new GqlSettings("", "", "", "", 500, "", null, "", "", "", "", "", "", true, false, CustomGptConstants.DEFAULT_CUSTOM_GPT_API_BASE_URL);
        }
        try {
            final Service service = BundleUtils.getOsgiService(Service.class, null);
            final String projectName = service != null ? service.getProjectName() : null;
            return new GqlSettings(
                    String.join(",", config.getContentIndexedMainResources()),
                    String.join(",", config.getContentIndexedSubNodes()),
                    String.join(",", config.getIndexedFileExtensions()),
                    "",
                    config.getBulkOperationsBatchSize(),
                    config.getCustomGptProjectId(),
                    projectName,
                    config.getCustomGptToken(),
                    config.getJahiaUsername(),
                    config.getJahiaPassword(),
                    config.getJahiaServerCookieName(),
                    config.getJahiaServerCookieValue(),
                    config.getJahiaServerCookieDomain(),
                    config.isDryRun(),
                    config.isScheduleJobASAP(),
                    config.getCustomGptApiBaseUrl()
            );
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
