package org.jahia.community.modules.customgpt.service;

import java.util.*;
import javax.jcr.RepositoryException;
import org.jahia.community.modules.customgpt.CustomGptConstants;
import org.jahia.community.modules.customgpt.CustomGptRequest;
import org.jahia.community.modules.customgpt.indexer.builder.ContentIndexBuilder;
import org.jahia.community.modules.customgpt.indexer.builder.FileIndexBuilder;
import org.jahia.community.modules.customgpt.indexer.builder.IndexBuilder;
import org.jahia.community.modules.customgpt.settings.NotConfiguredException;
import org.jahia.community.modules.customgpt.util.Utils;
import org.jahia.services.content.JCRNodeWrapper;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(service = IndexService.class)
/**
 * Entry point used by other services to build indexing requests. It delegates
 * the work to dedicated {@link IndexBuilder} implementations depending on the
 * type of content.
 */
public class IndexService {

    private static final Logger LOGGER = LoggerFactory.getLogger(IndexService.class);
    private final List<IndexBuilder> indexBuilders = new ArrayList<>(2);
    private ContentIndexBuilder contentIndexBuilder;
    private FileIndexBuilder fileIndexBuilder;

    @Reference(service = ContentIndexBuilder.class)
    public void setContentIndexBuilder(ContentIndexBuilder contentIndexBuilder) {
        this.contentIndexBuilder = contentIndexBuilder;
        this.indexBuilders.add(contentIndexBuilder);
    }

    @Reference(service = FileIndexBuilder.class)
    public void setFileIndexBuilder(FileIndexBuilder fileIndexBuilder) {
        this.fileIndexBuilder = fileIndexBuilder;
        this.indexBuilders.add(fileIndexBuilder);
    }

    public void addIndexRequests(JCRNodeWrapper node, String language, Set<CustomGptRequest> requests) throws RepositoryException, NotConfiguredException {
        final IndexBuilder builder = getIndexBuilder(Utils.getIndexType(node));
        builder.addIndexRequests(node, language, requests);
    }

    public IndexBuilder getIndexBuilder(CustomGptConstants.IndexType indexType) {
        switch (indexType) {
            case FILE:
                return fileIndexBuilder;
            default:
                return contentIndexBuilder;
        }
    }

    public Set<String> getIndexedMainResourceNodeTypes() throws NotConfiguredException {
        final Set<String> result = new HashSet<>();
        for (IndexBuilder ib : indexBuilders) {
            result.addAll(ib.getIndexedMainResourceNodeTypes());
        }
        return result;
    }

    public Set<String> getIndexedSubNodeTypes() throws NotConfiguredException {
        final Set<String> result = new HashSet<>();
        for (IndexBuilder ib : indexBuilders) {
            result.addAll(ib.getIndexedSubNodeTypes());
        }
        return result;
    }

    public Set<String> getNodePathsToIndex(JCRNodeWrapper node) throws RepositoryException, NotConfiguredException {
        final Set<String> result = new HashSet<>();
        for (IndexBuilder ib : indexBuilders) {
            result.addAll(ib.getNodePathsToIndex(node));
        }
        return result;
    }

    public JCRNodeWrapper getParentDisplayableNode(JCRNodeWrapper nestedNode, String index) throws NotConfiguredException {
        return contentIndexBuilder.getMainResourceNode(nestedNode);
    }
}
