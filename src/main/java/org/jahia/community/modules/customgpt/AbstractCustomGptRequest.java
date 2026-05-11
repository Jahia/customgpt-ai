package org.jahia.community.modules.customgpt;

import java.util.Objects;
import org.jahia.services.content.JCRNodeWrapper;

public abstract class AbstractCustomGptRequest<R extends AbstractCustomGptRequest<R>> implements CustomGptRequest<R> {

    private final JCRNodeWrapper node;
    private final String language;

    public AbstractCustomGptRequest(JCRNodeWrapper node, String language) {
        this.node = node;
        this.language = language;
    }

    public JCRNodeWrapper getNode() {
        return node;
    }

    public String getLanguage() {
        return language;
    }

    @Override
    public int hashCode() {
        return Objects.hash(node.getPath(), language);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        final AbstractCustomGptRequest otherObj = (AbstractCustomGptRequest) obj;
        return Objects.equals(node.getPath(), otherObj.node.getPath()) && Objects.equals(language, otherObj.language);
    }

    @Override
    public String toString() {
        return String.format("Node %s, language %s", node.getPath(), language);
    }

}
