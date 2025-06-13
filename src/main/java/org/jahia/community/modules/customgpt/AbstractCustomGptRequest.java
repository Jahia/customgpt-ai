package org.jahia.community.modules.customgpt;

import com.google.gwt.thirdparty.guava.common.base.Objects;
import org.jahia.services.content.JCRNodeWrapper;

/**
 * Base implementation of {@link CustomGptRequest} providing the common
 * properties required for all requests.
 *
 * @param <R> concrete request type
 */
public abstract class AbstractCustomGptRequest<R extends AbstractCustomGptRequest<R>> implements CustomGptRequest<R> {

    /** Node associated with the request. */
    private final JCRNodeWrapper node;
    /** Language of the content. */
    private final String language;

    /**
     * Builds a new request for the given node and language.
     *
     * @param node      JCR node involved in the operation
     * @param language  language of the node being processed
     */
    public AbstractCustomGptRequest(JCRNodeWrapper node, String language) {
        this.node = node;
        this.language = language;
    }

    /**
     * Returns the JCR node associated with the request.
     *
     * @return the node
     */
    public JCRNodeWrapper getNode() {
        return node;
    }

    /**
     * Returns the language code used for indexing.
     *
     * @return the language
     */
    public String getLanguage() {
        return language;
    }

    /**
     * Computes a hash from the node path and language.
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(node.getPath(), language);
    }

    /**
     * Two requests are considered equal when they target the same node path and language.
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        final AbstractCustomGptRequest otherObj = (AbstractCustomGptRequest) obj;
        return Objects.equal(node.getPath(), otherObj.node.getPath()) && Objects.equal(language, otherObj.language);
    }

    /**
     * Human readable representation of this request.
     */
    @Override
    public String toString() {
        return String.format("Node %s, language %s", node.getPath(), language);
    }

}
