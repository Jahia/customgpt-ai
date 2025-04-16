package org.jahia.community.modules.customgpt.indexer.builder;

import java.util.Set;
import javax.jcr.RepositoryException;
import org.jahia.community.modules.customgpt.CustomGptRequest;
import org.jahia.community.modules.customgpt.settings.NotConfiguredException;
import org.jahia.services.content.JCRNodeWrapper;

public interface IndexBuilder {

    boolean isNodeAccepted(JCRNodeWrapper node, boolean asReference) throws RepositoryException, NotConfiguredException;

    boolean isNodeAMainResource(JCRNodeWrapper node) throws RepositoryException, NotConfiguredException;

    JCRNodeWrapper getMainResourceNode(JCRNodeWrapper node) throws NotConfiguredException;

    Set<String> getIndexedMainResourceNodeTypes() throws NotConfiguredException;

    Set<String> getIndexedSubNodeTypes() throws NotConfiguredException;

    Set<String> getNodePathsToIndex(JCRNodeWrapper node) throws RepositoryException, NotConfiguredException;

    void addIndexRequests(JCRNodeWrapper node, String language, Set<CustomGptRequest> requests)
            throws RepositoryException, NotConfiguredException;

}
