package org.jahia.community.modules.customgpt.indexer.listener;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
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
import org.quartz.SchedulerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    @Override
    public void onEvent(EventIterator events) {
        try {
            final IndexOperations customGptIndexOperations = new IndexOperations();

            while (events.hasNext()) {
                final EventWrapper event = (EventWrapper) events.nextEvent();
                if (!service.acceptablePathToIndex(event.getPath(), null)) {
                    continue;
                }

                final String path = event.getPath();
                final String srcPath = (String) event.getInfo().get("srcAbsPath");

                // only need to process the node created event to index it
                final int type = event.getType();
                final String nodePath;
                final boolean isPropertyEvent = (type | PROPERTY_EVENTS) == PROPERTY_EVENTS;
                if (isPropertyEvent) {
                    final int endIndex = path.lastIndexOf(CustomGptConstants.PATH_DELIMITER);
                    nodePath = path.substring(0, endIndex);
                } else {
                    nodePath = path;
                }

                if (event.getPath().startsWith("/trash-")) {
                    final String identifier = event.getIdentifier();
                    // Deletion of the main resources

                    JCRTemplate.getInstance().doExecuteWithSystemSessionAsUser(null, Constants.LIVE_WORKSPACE, null, new JCRCallback<Void>() {
                        @Override
                        public Void doInJCR(JCRSessionWrapper session) throws RepositoryException {
                            try {
                                final JCRNodeWrapper nodeWrapper = session.getNodeByIdentifier(identifier);
                                boolean isMainResourceType = false;
                                for (String type : customGptConfig.getContentIndexedMainResources()) {
                                    isMainResourceType = isMainResourceType || nodeWrapper.isNodeType(type);
                                }
                                if (isMainResourceType) {
                                    if (nodeWrapper.hasProperty(CustomGptConstants.PROP_CUSTOM_GPT_PAGE_ID)) {
                                        final String customGtpPageId = nodeWrapper.getProperty(CustomGptConstants.PROP_CUSTOM_GPT_PAGE_ID).getString();
                                        processEvent(new CustomEvent(Event.NODE_REMOVED, null, null, customGtpPageId), null, customGptIndexOperations);
                                    }
                                } // Deletion of the sub-nodes
                                else {
                                    boolean isSubNodeType = false;
                                    for (String mainResourceType : customGptConfig.getContentIndexedSubNodes()) {
                                        isSubNodeType = isMainResourceType || nodeWrapper.isNodeType(mainResourceType);
                                    }
                                    if (isSubNodeType) {
                                        processEvent(new CustomEvent(Event.NODE_REMOVED, identifier, nodePath), nodePath, customGptIndexOperations);
                                    }
                                }
                            } catch (Exception e) {
                            }
                            return null;
                        }
                    });

                } else {
                    JCRTemplate.getInstance().doExecuteWithSystemSessionAsUser(null, Constants.LIVE_WORKSPACE, null, new JCRCallback<Void>() {
                        @Override
                        public Void doInJCR(JCRSessionWrapper session) throws RepositoryException {
                            try {
                                final String identifier = event.getIdentifier();
                                final JCRNodeWrapper nodeWrapper = session.getNodeByIdentifier(identifier);
                                boolean isMainResourceType = false;
                                final Set<String> mainResourceTypes = customGptConfig.getContentIndexedMainResources();
                                for (String mainResourceType : mainResourceTypes) {
                                    isMainResourceType = isMainResourceType || nodeWrapper.isNodeType(mainResourceType);
                                }
                                if (isMainResourceType) {
                                    processEvent(event, nodePath, customGptIndexOperations);
                                } else {
                                    boolean isSubNodeType = false;
                                    for (String subNodeType : customGptConfig.getContentIndexedSubNodes()) {
                                        isSubNodeType = isMainResourceType || nodeWrapper.isNodeType(subNodeType);
                                    }
                                    if (isSubNodeType) {
                                        for (String mainResourceType : mainResourceTypes) {
                                            final JCRNodeWrapper parentMainResource = JCRContentUtils.getParentOfType(nodeWrapper, mainResourceType);
                                            if (parentMainResource != null) {
                                                processEvent(event, parentMainResource.getPath(), customGptIndexOperations);
                                            }
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                LOGGER.error("Error processing events in the customGpt listener", e);
                            }
                            return null;
                        }
                    });
                }
            }

            if (!customGptIndexOperations.getOperations().isEmpty()) {
                LOGGER.debug("Triggering {} index operation(s)", customGptIndexOperations.getOperations().size());
                service.produceAsynchronousOperations(customGptIndexOperations);
            }
        } catch (RepositoryException | NotConfiguredException ex) {
            LOGGER.error("Error processing events in the customGpt listener", ex);
        }
    }

    private void processEvent(Event event, String nodePath, IndexOperations customGptIndexOperations)
            throws RepositoryException, NotConfiguredException, SchedulerException {
        switch (event.getType()) {
            case Event.NODE_REMOVED:
                final Map info = event.getInfo();
                final IndexOperations.CustomGptIndexOperation operation = new IndexOperations.CustomGptIndexOperation(IndexOperations.CustomGptOperationType.NODE_REMOVE, nodePath, event.getPath(), event.getIdentifier());
                if (info.containsKey(CustomGptConstants.PROP_CUSTOM_GPT_PAGE_ID)) {
                    operation.setCustomGptPageId(info.get(CustomGptConstants.PROP_CUSTOM_GPT_PAGE_ID).toString());

                }
                customGptIndexOperations.addOperation(operation);
                break;
            case Event.PROPERTY_ADDED:
                if (event.getPath().endsWith("/j:lastPublished")) {
                    addIndexOperation(nodePath, customGptIndexOperations);
                }
                break;
            case Event.PROPERTY_CHANGED:
                if (event.getPath().endsWith("/j:lastPublished")) {
                    addIndexOperation(nodePath, customGptIndexOperations);
                }
                break;
            default:
                break;
        }
    }

    private void addIndexOperation(String nodePath, IndexOperations indexOperations) throws RepositoryException {
        indexOperations.addOperation(new IndexOperations.CustomGptIndexOperation(IndexOperations.CustomGptOperationType.NODE_INDEX, nodePath));
    }

    @Override
    public String toString() {
        return IndexerJCRListener.class.getName() + "[workspace: " + getWorkspace() + "]";
    }
}
