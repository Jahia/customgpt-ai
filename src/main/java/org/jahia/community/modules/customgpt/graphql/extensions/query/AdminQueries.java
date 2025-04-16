package org.jahia.community.modules.customgpt.graphql.extensions.query;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import java.util.Collection;
import javax.jcr.AccessDeniedException;
import javax.jcr.RepositoryException;
import org.jahia.community.modules.customgpt.CustomGptConstants;
import org.jahia.community.modules.customgpt.graphql.extensions.models.GqlSiteListModel;
import org.jahia.community.modules.customgpt.service.Service;
import org.jahia.community.modules.customgpt.service.models.Site;
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

    private void checkAdminPermission(String path, String permission) throws RepositoryException {
        if (!JCRSessionFactory.getInstance().getCurrentUserSession().getNode(path).hasPermission(permission)) {
            throw new AccessDeniedException(permission);
        }
    }
}
