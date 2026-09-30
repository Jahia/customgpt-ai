package org.jahia.community.modules.customgpt.indexer.listener;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.jcr.RepositoryException;
import javax.jcr.observation.Event;
import javax.jcr.observation.EventIterator;
import org.jahia.api.Constants;
import org.jahia.community.modules.customgpt.CustomGptConstants;
import org.jahia.community.modules.customgpt.service.Service;
import org.jahia.community.modules.customgpt.settings.Config;
import org.jahia.community.modules.customgpt.settings.NotConfiguredException;
import org.jahia.services.content.*;
import org.jahia.services.content.JCRObservationManager.EventWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JCR observation listener registered on the live workspace.
 * Listens to node add/remove and property change events; a {@code j:lastPublished} property change
 * is the trigger for an index operation, while a NODE_REMOVED event (or trash move) triggers a delete.
 * Nodes carrying {@code jmix:skipCustomGptIndexation} are excluded.
 */
public class IndexerJCRListener extends DefaultEventListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(IndexerJCRListener.class);
    private static final int PROPERTY_EVENTS = Event.PROPERTY_CHANGED + Event.PROPERTY_ADDED + Event.PROPERTY_REMOVED;
    private final Config customGptConfig;
    private final Service service;

    public IndexerJCRListener(boolean availableDuringPublish, Service customGptService, Config customGptConfig) {
        super();
        this.customGptConfig = customGptConfig;
        this.availableDuringPublish = availableDuringPublish;
        this.service = customGptService;
        propertiesToIgnore.add(CustomGptConstants.PROP_CUSTOM_GPT_PAGE_ID);
        propertiesToIgnore.add(Constants.JCR_MIXINTYPES);
        setWorkspace(Constants.LIVE_WORKSPACE);

    }

    @Override
    public String[] getNodeTypes() {
        final Set<String> nodeTypes = new HashSet<>();
        try {
            nodeTypes.addAll(service.getIndexedMainResourceNodeTypes());
            nodeTypes.addAll(service.getIndexedSubNodeTypes());
        } catch (NotConfiguredException ex) {
            LOGGER.error("Issue retrieving node types", ex);
        }
        return nodeTypes.toArray(new String[0]);
    }

    @Override
    public int getEventTypes() {
        return Event.NODE_ADDED + Event.NODE_REMOVED + PROPERTY_EVENTS;
    }

    /**
     * Entry point called by {@code JCRObservationManager.consume}, which runs listeners inline inside
     * {@code JCRSessionWrapper.save()}.
     *
     * <p>Jahia wraps this call in {@code catch (Exception)} and logs a WARN, so an ordinary exception is already
     * contained by the platform. An {@link Error} is <em>not</em>: it propagates out of {@code consume} and aborts
     * the caller's save - a publication, an import, an editor's content change - for a failure that has nothing to
     * do with the content being saved. That is how a {@link NoClassDefFoundError} from a stale listener killed a
     * {@code PublicationJob} in JAHIACOM-1675, and it is why {@link LinkageError} is caught below.
     *
     * <p>The {@link RuntimeException} arm is not load-bearing for the save - the platform would have swallowed it
     * anyway - but it lets this module log the failure with its own context instead of a context-free core WARN.
     */
    @Override
    public void onEvent(EventIterator events) {
        try {
            final IndexOperations customGptIndexOperations = new IndexOperations();
            while (events.hasNext()) {
                final EventWrapper event = (EventWrapper) events.nextEvent();
                if (!service.acceptablePathToIndex(event.getPath())) {
                    continue;
                }
                handleSingleEvent(event, customGptIndexOperations);
            }
            if (!customGptIndexOperations.getOperations().isEmpty()) {
                LOGGER.debug("Triggering {} index operation(s)", customGptIndexOperations.getOperations().size());
                service.produceAsynchronousOperations(customGptIndexOperations);
            }
        } catch (RepositoryException ex) {
            LOGGER.error("Error processing events in the customGpt listener", ex);
        } catch (RuntimeException ex) {
            LOGGER.error("Unexpected error processing events in the customGpt listener", ex);
        } catch (LinkageError err) {
            // The load-bearing arm. A LinkageError naming a class this bundle owns usually means this listener has
            // outlived its bundle classloader; one naming someone else's class is a different problem. Either way
            // it must not escape, because Jahia does not contain Errors and this one would abort the caller's save.
            // We deliberately do NOT disable the listener on this: deciding "my classloader is dead" from a message
            // string is unreliable in both directions, and a permanent, restart-only off switch is too costly to
            // hang off an unreliable signal.
            LOGGER.error("Error processing events in the customGpt listener: could not load {}. If that class"
                    + " belongs to customgpt-ai, this listener has most likely outlived its bundle classloader"
                    + " (the module was updated, refreshed or uninstalled without the listener being"
                    + " unregistered) and the node needs restarting.", err.getMessage(), err);
        }
    }

    private void handleSingleEvent(EventWrapper event, IndexOperations customGptIndexOperations) throws RepositoryException {
        final String path = event.getPath();
        final String nodePath = computeNodePath(path, event.getType());

        if (isSkipIndexMixinChange(event)) {
            handleSkipIndexMixinEvent(event, nodePath, customGptIndexOperations);
        } else if (event.getPath().startsWith("/trash-")) {
            handleTrashEvent(event, nodePath, customGptIndexOperations);
        } else {
            handleRegularEvent(event, nodePath, customGptIndexOperations);
        }
    }

    private static String computeNodePath(String path, int type) {
        final boolean isPropertyEvent = (type & PROPERTY_EVENTS) != 0;
        if (isPropertyEvent) {
            final int endIndex = path.lastIndexOf(CustomGptConstants.PATH_DELIMITER);
            return path.substring(0, endIndex);
        }
        return path;
    }

    private static boolean isSkipIndexMixinChange(EventWrapper event) throws RepositoryException {
        return event.getPath().endsWith(Constants.JCR_MIXINTYPES)
                && event.getType() == Event.PROPERTY_CHANGED
                && event.getNodeTypes().contains(CustomGptConstants.MIX_SKIP_INDEX);
    }

    private void handleSkipIndexMixinEvent(EventWrapper event, String nodePath, IndexOperations customGptIndexOperations) throws RepositoryException {
        final String identifier = event.getIdentifier();
        try {
            JCRTemplate.getInstance().doExecuteWithSystemSessionAsUser(null, Constants.LIVE_WORKSPACE, null, session -> {
                try {
                    final JCRNodeWrapper nodeWrapper = session.getNodeByIdentifier(identifier);
                    if (isMainResourceType(nodeWrapper)) {
                        tryQueueMappingRemoval(nodeWrapper, identifier, customGptIndexOperations);
                    } else if (isSubNodeType(nodeWrapper)) {
                        processEvent(new CustomEvent(Event.NODE_REMOVED, identifier, nodePath), nodePath, customGptIndexOperations);
                    }
                } catch (RepositoryException | NotConfiguredException e) {
                    LOGGER.warn("Error processing JCR event for skip-index mixin on node {}", identifier, e);
                }
                return null;
            });
        } catch (RepositoryException e) {
            LOGGER.warn("Error executing session for skip-index mixin on node {}", identifier, e);
        }
    }

    private void handleTrashEvent(EventWrapper event, String nodePath, IndexOperations customGptIndexOperations) throws RepositoryException {
        final String identifier = event.getIdentifier();
        // srcAbsPath is the original site path before the node was moved to trash
        final String originalPath = (String) event.getInfo().get("srcAbsPath");
        try {
            JCRTemplate.getInstance().doExecuteWithSystemSessionAsUser(null, Constants.LIVE_WORKSPACE, null, session -> {
                try {
                    final JCRNodeWrapper nodeWrapper = session.getNodeByIdentifier(identifier);
                    if (isMainResourceType(nodeWrapper)) {
                        if (originalPath != null) {
                            findAndQueueMappingRemoval(originalPath, customGptIndexOperations);
                        } else {
                            LOGGER.warn("Cannot determine original path for deleted node {}, skipping CustomGPT cleanup", identifier);
                        }
                    } else if (isSubNodeType(nodeWrapper)) {
                        processEvent(new CustomEvent(Event.NODE_REMOVED, identifier, nodePath), nodePath, customGptIndexOperations);
                    }
                } catch (RepositoryException | NotConfiguredException e) {
                    LOGGER.warn("Error processing trash JCR event for node {}", identifier, e);
                }
                return null;
            });
        } catch (RepositoryException e) {
            LOGGER.warn("Error executing session for trash event on node {}", identifier, e);
        }
    }

    private void handleRegularEvent(EventWrapper event, String nodePath, IndexOperations customGptIndexOperations) throws RepositoryException {
        try {
            JCRTemplate.getInstance().doExecuteWithSystemSessionAsUser(null, Constants.LIVE_WORKSPACE, null, session -> {
                try {
                    final JCRNodeWrapper nodeWrapper = session.getNodeByIdentifier(event.getIdentifier());
                    final Set<String> mainResourceTypes = customGptConfig.getContentIndexedMainResources();
                    if (isNodeOfAnyType(nodeWrapper, mainResourceTypes)) {
                        processEvent(event, nodePath, customGptIndexOperations);
                    } else if (isSubNodeType(nodeWrapper)) {
                        queueIndexationForSubNodeParents(event, nodeWrapper, mainResourceTypes, customGptIndexOperations);
                    }
                } catch (RepositoryException | NotConfiguredException e) {
                    LOGGER.error("Error processing events in the customGpt listener", e);
                }
                return null;
            });
        } catch (RepositoryException e) {
            LOGGER.error("Error executing session in customGpt listener", e);
        }
    }

    private void queueIndexationForSubNodeParents(EventWrapper event, JCRNodeWrapper nodeWrapper,
            Set<String> mainResourceTypes, IndexOperations customGptIndexOperations)
            throws RepositoryException {
        for (String mainResourceType : mainResourceTypes) {
            final JCRNodeWrapper parentMainResource = JCRContentUtils.getParentOfType(nodeWrapper, mainResourceType);
            if (parentMainResource != null) {
                processEvent(event, parentMainResource.getPath(), customGptIndexOperations);
            }
        }
    }

    private boolean isMainResourceType(JCRNodeWrapper nodeWrapper) throws RepositoryException, NotConfiguredException {
        return isNodeOfAnyType(nodeWrapper, customGptConfig.getContentIndexedMainResources());
    }

    private boolean isSubNodeType(JCRNodeWrapper nodeWrapper) throws RepositoryException, NotConfiguredException {
        return isNodeOfAnyType(nodeWrapper, customGptConfig.getContentIndexedSubNodes());
    }

    private static boolean isNodeOfAnyType(JCRNodeWrapper nodeWrapper, Set<String> nodeTypes) throws RepositoryException {
        for (String type : nodeTypes) {
            if (nodeWrapper.isNodeType(type)) {
                return true;
            }
        }
        return false;
    }

    private void processEvent(Event event, String nodePath, IndexOperations customGptIndexOperations)
            throws RepositoryException {
        switch (event.getType()) {
            case Event.NODE_REMOVED:
                final Map<?, ?> info = event.getInfo();
                final IndexOperations.CustomGptIndexOperation operation = new IndexOperations.CustomGptIndexOperation(IndexOperations.CustomGptOperationType.NODE_REMOVE, nodePath, event.getPath(), event.getIdentifier());
                if (info.containsKey(CustomGptConstants.PROP_CUSTOM_GPT_PAGE_ID)) {
                    operation.setCustomGptPageId(info.get(CustomGptConstants.PROP_CUSTOM_GPT_PAGE_ID).toString());

                }
                customGptIndexOperations.addOperation(operation);
                break;
            case Event.PROPERTY_ADDED:
            case Event.PROPERTY_CHANGED:
                if (event.getPath().endsWith("/j:lastPublished")) {
                    addIndexOperation(nodePath, customGptIndexOperations);
                }
                break;
            default:
                break;
        }
    }

    private void addIndexOperation(String nodePath, IndexOperations indexOperations) {
        indexOperations.addOperation(new IndexOperations.CustomGptIndexOperation(IndexOperations.CustomGptOperationType.NODE_INDEX, nodePath));
    }

    private void findAndQueueMappingRemoval(String nodePath, IndexOperations operations) {
        try {
            JCRTemplate.getInstance().doExecuteWithSystemSessionAsUser(null, Constants.EDIT_WORKSPACE, null, editSession -> {
                final String mappingPath = CustomGptConstants.buildMappingPath(nodePath);
                if (editSession.nodeExists(mappingPath)) {
                    final JCRNodeWrapper mappingNode = editSession.getNode(mappingPath);
                    if (mappingNode.hasProperty(CustomGptConstants.PROP_CUSTOM_GPT_PAGE_ID)) {
                        final String pageId = mappingNode.getProperty(CustomGptConstants.PROP_CUSTOM_GPT_PAGE_ID).getString();
                        processEvent(new CustomEvent(Event.NODE_REMOVED, null, null, pageId), null, operations);
                    }
                    mappingNode.remove();
                    editSession.save();
                }
                return null;
            });
        } catch (RepositoryException e) {
            LOGGER.warn("Error accessing CustomGPT mapping node for node path {}", nodePath, e);
        }
    }

    private void tryQueueMappingRemoval(JCRNodeWrapper node, String identifier, IndexOperations ops) {
        try {
            findAndQueueMappingRemoval(node.getPath(), ops);
        } catch (RuntimeException e) {
            LOGGER.warn("Cannot resolve path for node {}, skipping CustomGPT cleanup", identifier, e);
        }
    }

    @Override
    public String toString() {
        return IndexerJCRListener.class.getName() + "[workspace: " + getWorkspace() + "]";
    }
}
