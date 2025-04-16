package org.jahia.community.modules.customgpt.indexer;

import java.util.ArrayList;
import java.util.List;
import javax.jcr.AccessDeniedException;
import javax.jcr.RepositoryException;
import org.jahia.community.modules.customgpt.CustomGptConstants;
import org.jahia.community.modules.customgpt.graphql.extensions.models.GqlIndexingJob;
import org.jahia.community.modules.customgpt.graphql.extensions.models.GqlProjectInfo;
import org.jahia.community.modules.customgpt.indexer.listener.IndexOperations;
import org.jahia.community.modules.customgpt.indexer.listener.IndexOperations.CustomGptOperationType;
import org.jahia.community.modules.customgpt.service.Service;
import org.jahia.community.modules.customgpt.settings.NotConfiguredException;
import org.jahia.osgi.BundleUtils;
import org.jahia.services.content.JCRSessionFactory;

public class NodeReindexAsyncJob {

    public static GqlIndexingJob triggerJob(List<String> nodePaths, boolean inclDescendants)
            throws NotConfiguredException, RepositoryException {
        final Service customGptService = BundleUtils.getOsgiService(Service.class, null);

        final List<IndexOperations> operations = initIndexOperations();
        for (String nodePath : nodePaths) {
            checkAdminPermission(nodePath);
            for (IndexOperations operation : operations) {
                IndexOperations.CustomGptOperationType indexOp = (inclDescendants) ? CustomGptOperationType.TREE_INDEX : CustomGptOperationType.NODE_INDEX;
                operation.addOperation(new IndexOperations.CustomGptIndexOperation(indexOp, nodePath));
            }
        }

        customGptService.produceAsynchronousOperations(operations.toArray(new IndexOperations[]{}));
        return new GqlIndexingJob("Node reindex Job", new GqlProjectInfo("ALL"), GqlIndexingJob.JobStatus.STARTED);
    }

    private static List<IndexOperations> initIndexOperations() throws NotConfiguredException {
        final List<IndexOperations> indexOps = new ArrayList<>();
        final IndexOperations op = createIndexOperation();
        indexOps.add(op);
        return indexOps;
    }

    private static void checkAdminPermission(String path) throws RepositoryException {
        if (!JCRSessionFactory.getInstance().getCurrentUserSession().getNode(path).getResolveSite().hasPermission(CustomGptConstants.PERM_SITE_ADMIN)) {
            throw new AccessDeniedException(CustomGptConstants.PERM_SITE_ADMIN);
        }
    }

    private static IndexOperations createIndexOperation() {
        return new IndexOperations();
    }
}
