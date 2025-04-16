package org.jahia.community.modules.customgpt.indexer.builder;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Level;
import javax.jcr.RepositoryException;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.jahia.api.Constants;
import org.jahia.community.modules.customgpt.CustomGptConstants;
import org.jahia.community.modules.customgpt.CustomGptRequest;
import org.jahia.community.modules.customgpt.IndexRequest;
import org.jahia.community.modules.customgpt.service.Service;
import org.jahia.community.modules.customgpt.settings.Config;
import org.jahia.community.modules.customgpt.settings.NotConfiguredException;
import org.jahia.services.content.JCRContentUtils;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.sites.JahiaSitesService;
import org.json.JSONException;
import org.json.JSONObject;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractIndexBuilder implements IndexBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractIndexBuilder.class);
    protected static final String CONFIG_LOCATION = "META-INF/configurations";
    protected Config customGptConfig;
    protected BundleContext bundleContext;
    private Service customGptService;

    @Override
    public boolean isNodeAccepted(JCRNodeWrapper node, boolean asReference) throws NotConfiguredException {
        try {
            final String path = node.getPath();
            if (!path.startsWith(CustomGptConstants.PATH_SITES)) {
                return false;
            }

            // do not index nodes from system site unless it's a reference
            if (!asReference && JahiaSitesService.SYSTEM_SITE_KEY.equals(JCRContentUtils.getSiteKey(path))) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Rejected node {} of type {} because it's part of the system site.", path, node.getPrimaryNodeTypeName());
                }
                return false;
            }

            return isNodeAccepted(node);
        } catch (RepositoryException e) {
            LOGGER.error("Rejected node {} because an error occurred while determining its metadata", node, e);
            return false;
        }
    }

    @Override
    public void addIndexRequests(JCRNodeWrapper node, String language, Set<CustomGptRequest> requests)
            throws RepositoryException, NotConfiguredException {
        if (node.isFile() && !fileExtensionIsAccepted(node)) {
            return;
        }
        try {
            if (isIndexedMainResource(node, true)) {
                indexNode(node, language, requests);
            }
            if (isIndexedSubNodeType(node)) {
                final JCRNodeWrapper nodeWrapper = getMainResourceNode(node);
                if (nodeWrapper != null) {
                    indexNode(nodeWrapper, language, requests);
                }
            }
        } catch (RepositoryException | NotConfiguredException e) {
            LOGGER.warn("Skipping node {} indexation due to {}", node.getPath(), e.getMessage(), e);
        }
    }

    @Override
    public JCRNodeWrapper getMainResourceNode(JCRNodeWrapper node) throws NotConfiguredException {
        for (String indexedMainResourceTypes : getIndexedMainResourceNodeTypes()) {
            try {
                final JCRNodeWrapper parentMainResource = JCRContentUtils.getParentOfType(node, indexedMainResourceTypes);
                if (parentMainResource != null && !customGptService.skipIndexationForNode(parentMainResource)) {
                    return parentMainResource;
                }
            } catch (RepositoryException ex) {
                LOGGER.warn("Impossible to check if node {} should be skipped indexation", node.getPath(), ex);
            }
        }
        return null;
    }

    protected final JSONObject getDefaultConfigResource(String resourcePath) {
        final URL configResourceURL = bundleContext.getBundle().getResource(CONFIG_LOCATION + resourcePath);
        if (configResourceURL != null) {
            try {
                return new JSONObject(IOUtils.toString(new InputStreamReader(configResourceURL.openStream(), StandardCharsets.UTF_8)));
            } catch (JSONException | IOException e) {
                LOGGER.error("Failed to read default configuration file for resourcePath {}: {}",
                        CONFIG_LOCATION + resourcePath, e.getMessage());
            }
        }
        return null;
    }

    private void indexNode(JCRNodeWrapper node, String language, Set<CustomGptRequest> requests)
            throws RepositoryException {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Generating customGPT request for main resource node: {}, in language {}", node.getPath(), language);
        }
        requests.add(new IndexRequest(node, language));
    }

    private boolean isIndexedMainResource(JCRNodeWrapper node, boolean mainResource) throws NotConfiguredException {
        try {
            if (!mainResource || customGptService.skipIndexationForNode(node)) {
                return false;
            }

            for (String mrType : getIndexedMainResourceNodeTypes()) {
                if (node.isNodeType(mrType)) {
                    return true;
                }
            }
        } catch (RepositoryException e) {
            LOGGER.warn("Unable to check if node is a main resource or not, skip indexation : {}", e.getMessage());
        }
        return false;
    }

    private boolean isIndexedSubNodeType(JCRNodeWrapper node) throws NotConfiguredException {
        try {
            for (String mrType : getIndexedSubNodeTypes()) {
                if (node.isNodeType(mrType)) {
                    return true;
                }
            }
        } catch (RepositoryException e) {
            LOGGER.warn("Unable to check if node is a main resource or not, skip indexation : {}", e.getMessage());
        }
        return false;
    }

    @Override
    public Set<String> getIndexedMainResourceNodeTypes() throws NotConfiguredException {
        final Set<String> mainResources = new LinkedHashSet<>();
        mainResources.addAll(getCustomGptConfig().getContentIndexedMainResources());
        return mainResources;
    }

    @Override
    public Set<String> getIndexedSubNodeTypes() throws NotConfiguredException {
        return getCustomGptConfig().getContentIndexedSubNodes();
    }

    public boolean isNodeAccepted(JCRNodeWrapper node) throws RepositoryException, NotConfiguredException {
        final Set<String> listOfTypes = new HashSet<>();
        listOfTypes.addAll(getIndexedMainResourceNodeTypes());
        listOfTypes.addAll(getIndexedSubNodeTypes());
        for (String type : listOfTypes) {
            if (node.isNodeType(type)) {
                return true;
            }
        }
        if (node.isNodeType(Constants.JAHIANT_RESOURCE)) {
            // do not accept thumbnail nodes
            return !StringUtils.equals(node.getName(), "thumbnail") && !StringUtils.equals(node.getName(), "thumbnail2");
        } else if (node.isNodeType(Constants.JAHIANT_FILE)) {
            return fileExtensionIsAccepted(node);
        }

        return false;
    }

    @Override
    public boolean isNodeAMainResource(JCRNodeWrapper node) throws RepositoryException, NotConfiguredException {
        for (String type : getIndexedMainResourceNodeTypes()) {
            if (node.isNodeType(type)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Set<String> getNodePathsToIndex(JCRNodeWrapper node) throws RepositoryException, NotConfiguredException {
        JCRNodeWrapper nodeToIndex = null;
        if (isNodeAccepted(node)) {
            if (node.isNodeType(Constants.JAHIANT_RESOURCE)) {
                nodeToIndex = node.getParent();
            } else {
                nodeToIndex = node;
            }
        }

        return nodeToIndex != null ? Collections.singleton(nodeToIndex.getPath()) : Collections.<String>emptySet();
    }

    public void setCustomGptService(Service customGptService) {
        this.customGptService = customGptService;
    }

    protected Config getCustomGptConfig() {
        return customGptConfig;
    }

    public void setCustomGptConfig(Config customGptConfig) {
        this.customGptConfig = customGptConfig;
    }

    private boolean fileExtensionIsAccepted(JCRNodeWrapper node) {
        final Set<String> indexedExtensions = customGptConfig.getIndexedFileExtensions();
        // Do not index files at all
        if (indexedExtensions.isEmpty()) {
            return false;
        }

        boolean result = indexedExtensions.stream().anyMatch(ext -> node.getName().endsWith("." + ext));;
        return result;
    }

}
