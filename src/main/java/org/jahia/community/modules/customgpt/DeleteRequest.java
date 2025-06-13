package org.jahia.community.modules.customgpt;

import org.jahia.services.content.JCRNodeWrapper;

/**
 * Request asking CustomGPT to remove the provided node from its index.
 */
public class DeleteRequest extends AbstractCustomGptRequest<DeleteRequest> {

    /**
     * Creates a new delete request for the given node.
     *
     * @param node     node to remove from the index
     * @param language language of the content
     */
    public DeleteRequest(JCRNodeWrapper node, String language) {
        super(node, language);
    }

    @Override
    public RequestType requestType() {
        return RequestType.DELETE;
    }
}
