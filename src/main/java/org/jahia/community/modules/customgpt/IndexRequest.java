package org.jahia.community.modules.customgpt;

import org.jahia.services.content.JCRNodeWrapper;

/**
 * Request asking CustomGPT to index the provided node.
 */
public class IndexRequest extends AbstractCustomGptRequest<IndexRequest> {

    /**
     * Creates a new index request for the given node.
     *
     * @param node     node to index
     * @param language language of the content
     */
    public IndexRequest(JCRNodeWrapper node, String language) {
        super(node, language);
    }

    @Override
    public RequestType requestType() {
        return RequestType.INDEX;
    }
}
