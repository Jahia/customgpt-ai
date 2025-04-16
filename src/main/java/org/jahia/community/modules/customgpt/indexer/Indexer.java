package org.jahia.community.modules.customgpt.indexer;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.query.Query;
import javax.jcr.query.QueryManager;
import okhttp3.OkHttpClient;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.jahia.api.Constants;
import org.jahia.community.modules.customgpt.CustomGptConstants;
import org.jahia.community.modules.customgpt.service.Service;
import org.jahia.community.modules.customgpt.settings.Config;
import org.jahia.community.modules.customgpt.settings.NotConfiguredException;
import org.jahia.exceptions.JahiaRuntimeException;
import org.jahia.services.content.JCRContentUtils;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.jahia.services.query.ScrollableQuery;
import org.jahia.services.query.ScrollableQueryCallback;
import org.jahia.services.usermanager.JahiaUser;
import org.jahia.services.usermanager.JahiaUserManagerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Indexer {

    private static final Logger LOGGER = LoggerFactory.getLogger(Indexer.class);
    private static final Map<String, AtomicInteger> nodeToReindex = new ConcurrentHashMap<>();
    private final Map<String, String> nodePathsToMove = new LinkedHashMap<>();
    private final Set<String> customGptPageToRemove = new LinkedHashSet<>();
    private final Set<String> nodePathsToAddOrReIndex = new LinkedHashSet<>();
    private final TreeSet<String> nodePathsToRemove = new TreeSet<>();
    private Config customGptConfig;
    private Service service;
    private JahiaUser rootUser;

    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .append("nodePathsToAddOrReIndex", String.join(";", nodePathsToAddOrReIndex))
                .append("nodePathsToMove", String.join(";", nodePathsToMove.keySet()))
                .append("nodePathsToRemove", String.join(";", nodePathsToRemove))
                .toString();
    }

    public Indexer(Service service, Config customGptConfig) {
        this.service = service;
        this.customGptConfig = customGptConfig;
    }

    public void init() throws RepositoryException {
        rootUser = JahiaUserManagerService.getInstance().lookupRootUser().getJahiaUser();
    }

    public void queueRequests(OkHttpClient customGptClient, OkHttpClient jahiaClient) throws RepositoryException, NotConfiguredException, IOException {
        if (isEmpty()) {
            LOGGER.debug("There is no node to remove, to index or customGpt pages to remove");
            return;
        }
        CustomGptIndexerNodeHandler.handleNodeToReindex(customGptClient, jahiaClient, this);
    }

    public boolean isEmpty() {
        boolean nodePathsEmpty = nodePathsToRemove.isEmpty() && nodePathsToAddOrReIndex.isEmpty()
                && nodePathsToMove.isEmpty() && customGptPageToRemove.isEmpty();
        return nodePathsEmpty;
    }

    public Collection<String> getNodePathsToRemove() {
        return Collections.unmodifiableSortedSet(nodePathsToRemove);
    }

    public void addNodeToDelete(String path) {
        addNodeToDelete(null, path);
    }

    public void addNodeToDelete(String customGptPageId, String path) {
        if (customGptPageId != null) {
            customGptPageToRemove.add(customGptPageId);
        }
        if (StringUtils.isBlank(path)) {
            return;
        }

        final Set<String> toRemove = new HashSet<>(nodePathsToRemove.size());
        final String pathWithDelimiter = path + CustomGptConstants.PATH_DELIMITER;
        final SortedSet<String> greaterPaths = nodePathsToRemove.tailSet(pathWithDelimiter);
        if (!greaterPaths.isEmpty()) {
            for (String greaterPath : greaterPaths) {
                if (!greaterPath.startsWith(pathWithDelimiter)) {
                    break;
                }
                toRemove.add(greaterPath);
            }
            nodePathsToRemove.removeAll(toRemove);
            nodePathsToRemove.add(path);
        } else {
            // check if we don't already have a lower path present that doesn't start with the path we're adding
            final String lower = nodePathsToRemove.lower(path);
            if (lower == null || !path.startsWith(lower + CustomGptConstants.PATH_DELIMITER)) {
                nodePathsToRemove.add(path);
            }
        }
        //no need to reindex paths to be removed
        nodePathsToAddOrReIndex.removeAll(nodePathsToRemove);
    }

    boolean containsNodePathToAddOrReIndex(String path) {
        return nodePathsToAddOrReIndex.contains(path);
    }

    public void addNodePathToIndex(String path) throws NotConfiguredException {
        if (StringUtils.isEmpty(path)) {
            return;
        }
        try {
            final JCRSessionWrapper systemSession = getSystemSession();
            final JCRNodeWrapper node = systemSession.getNode(path);
            final JCRNodeWrapper contentNode = getContentNode(node);

            if (!nodePathsToRemove.contains(path)) {
                nodePathsToAddOrReIndex.addAll(service.getNodePathsToIndex(contentNode));
            }
            systemSession.refresh(false);
        } catch (RepositoryException e) {
            // Skip the node as it no longer exists
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(e.getMessage(), e);
            }
        }
    }

    public void addNodePathToMove(String source, String target) {
        this.nodePathsToMove.put(source, target);
    }

    private JCRNodeWrapper getContentNode(JCRNodeWrapper nodeWrapper) throws RepositoryException {
        if (nodeWrapper.isNodeType(Constants.JAHIANT_TRANSLATION)
                || nodeWrapper.isNodeType(Constants.JAHIANT_ACL)
                || nodeWrapper.isNodeType(Constants.JAHIANT_CONDITIONAL_VISIBILITY)) {
            return nodeWrapper.getParent();
        }
        if (nodeWrapper.isNodeType(Constants.JAHIANT_ACE)
                || nodeWrapper.isNodeType(Constants.JAHIANT_CONDITION)) {
            return getContentNode(nodeWrapper.getParent());
        }
        if (nodeWrapper.isNodeType(Constants.JAHIANT_ACE)) {
            return getContentNode(nodeWrapper.getParent());
        }
        return nodeWrapper;
    }

    public void addSiteToIndex(OkHttpClient customGptClient, OkHttpClient jahiaClient, String path) throws RepositoryException, NotConfiguredException {
        if (StringUtils.isEmpty(path)) {
            return;
        }

        final JCRNodeWrapper node = getSystemSession().getNode(path);
        if (node instanceof JCRSiteNode) {
            final JCRSiteNode siteNode = (JCRSiteNode) node;
            if (siteNode.getPath().startsWith(CustomGptConstants.PATH_SITES)) {
                LOGGER.info("Indexing site {}...", siteNode.getPath());
                clearNodeToReindex();
                LOGGER.info("We cleared all blocked nodes from before reindexation.");
                addNodesToIndex(customGptClient, jahiaClient, siteNode);
                LOGGER.info("Finished indexing site {}", siteNode.getPath());
            }
        }
    }

    public void addNodesToIndex(OkHttpClient customGptClient, OkHttpClient jahiaClient, JCRNodeWrapper node) throws RepositoryException, NotConfiguredException {
        // index only main resources first, use for site and tree indexing
        indexSubNodes(customGptClient, jahiaClient, node, service.getIndexedMainResourceNodeTypes());
    }

    private void indexSubNodes(OkHttpClient customGpt, OkHttpClient jahiaClient, JCRNodeWrapper rootNode, Set<String> nodeTypes) throws RepositoryException, NotConfiguredException {
        for (String indexedNodeType : nodeTypes) {
            final JCRSessionWrapper systemSession = getSystemSession();
            final QueryManager manager = systemSession.getWorkspace().getQueryManager();
            final String statement = "select * from [" + indexedNodeType + "] as sel where isdescendantnode(sel,['" + rootNode.getPath() + "']) ORDER BY [jcr:uuid]";
            final Query query = manager.createQuery(statement, Query.JCR_SQL2);
            int step = customGptConfig.getBulkOperationsBatchSize();
            final ScrollableQuery scrollableQuery = new ScrollableQuery(step, query);
            scrollableQuery.execute(new QueueNodes(customGpt, jahiaClient, rootNode, nodeTypes));
            systemSession.refresh(false);
            JCRTemplate.getInstance().getSessionFactory().closeAllSessions();
        }
    }

    public Set<String> getNodePathsToAddOrReIndex() {
        return Collections.unmodifiableSet(nodePathsToAddOrReIndex);
    }

    public Set<String> getCustomGptPageToRemove() {
        return Collections.unmodifiableSet(customGptPageToRemove);
    }

    public Map<String, String> getNodePathsToMove() {
        return nodePathsToMove;
    }

    public boolean isMarkedForRemoval(String path) {
        if (nodePathsToRemove.contains(path)) {
            return true;
        } else {
            final String possibleToRemoveAncestorPath = nodePathsToRemove.lower(path);
            return possibleToRemoveAncestorPath != null && path.startsWith(possibleToRemoveAncestorPath + CustomGptConstants.PATH_DELIMITER);
        }
    }

    public void setService(Service service) {
        this.service = service;
    }

    Service getService() {
        return service;
    }

    public JCRSessionWrapper getSystemSession() throws RepositoryException {
        return service.getSystemSession(rootUser, Constants.LIVE_WORKSPACE, null);
    }

    public JCRSessionWrapper getSystemSession(Locale locale) throws RepositoryException {
        return service.getSystemSession(rootUser, Constants.LIVE_WORKSPACE, locale);
    }

    public void setCustomGptConfig(Config customGptConfig) {
        this.customGptConfig = customGptConfig;
    }

    public Config getCustomGptConfig() {
        return customGptConfig;
    }

    private class QueueNodes extends ScrollableQueryCallback<Void> {

        private final JCRNodeWrapper indexedNode;
        private final Set<String> nodeTypes;
        private final OkHttpClient customGptClient;
        private final OkHttpClient jahiaClient;
        private int nodeCounter;

        public QueueNodes(OkHttpClient customGptClient, OkHttpClient jahiaClient, JCRNodeWrapper indexedNode, Set<String> nodeTypes) {
            this.indexedNode = indexedNode;
            this.nodeCounter = 0;
            this.nodeTypes = nodeTypes;
            this.customGptClient = customGptClient;
            this.jahiaClient = jahiaClient;
        }

        @Override
        public boolean scroll() throws RepositoryException {
            final NodeIterator nodeIterator = stepResult.getNodes();
            while (nodeIterator.hasNext()) {
                final JCRNodeWrapper node = (JCRNodeWrapper) nodeIterator.nextNode();
                nodeCounter++;

                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Checking if path {} should be indexed", node.getPath());
                }

                for (String indexedMainResourceTypes : nodeTypes) {
                    if (node.isNodeType(indexedMainResourceTypes)) {
                        nodePathsToAddOrReIndex.add(node.getPath());
                        LOGGER.debug("Adding path {} to be indexed", node.getPath());
                    } else {
                        final JCRNodeWrapper parentMainResource = JCRContentUtils.getParentOfType(node, indexedMainResourceTypes);
                        if (parentMainResource != null && !nodePathsToAddOrReIndex.contains(parentMainResource.getPath())) {
                            nodePathsToAddOrReIndex.add(parentMainResource.getPath());
                            LOGGER.debug("Adding path {} to be indexed", parentMainResource.getPath());
                        }
                    }
                }

                try {
                    if ((nodeCounter % customGptConfig.getBulkOperationsBatchSize()) == 0 || !nodeIterator.hasNext()) {
                        LOGGER.debug("Starting to queue requests");
                        queueRequests(customGptClient, jahiaClient);
                        LOGGER.debug("Ending to queue requests");
                        nodePathsToAddOrReIndex.clear();
                        LOGGER.debug("Refreshing session internal cache.");
                        node.getSession().refresh(false);
                    }
                } catch (NotConfiguredException | IOException ex) {
                    throw new JahiaRuntimeException("Error while reindexing content in " + indexedNode.getPath(), ex);
                }
            }
            return true;
        }

        @Override
        protected Void getResult() {
            return null;
        }
    }

    private static void clearNodeToReindex() {
        nodeToReindex.clear();
    }
}
