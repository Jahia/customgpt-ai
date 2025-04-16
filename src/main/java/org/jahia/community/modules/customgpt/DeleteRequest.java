package org.jahia.community.modules.customgpt;

import org.jahia.services.content.JCRNodeWrapper;

public class DeleteRequest extends AbstractCustomGptRequest<DeleteRequest> {

    public DeleteRequest(JCRNodeWrapper node, String language) {
        super(node, language);
    }

    @Override
    public RequestType requestType() {
        return RequestType.DELETE;
    }
}
