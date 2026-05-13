package org.jahia.community.modules.customgpt;

import org.jahia.services.content.JCRNodeWrapper;

/** Request to add or update a JCR node in the CustomGPT project. */
public class IndexRequest extends AbstractCustomGptRequest<IndexRequest> {

    public IndexRequest(JCRNodeWrapper node, String language) {
        super(node, language);
    }

    @Override
    public RequestType requestType() {
        return RequestType.INDEX;
    }
}
