package org.jahia.community.modules.customgpt.indexer.listener;

import java.io.Serializable;
import java.util.Objects;
import java.util.LinkedHashSet;
import java.util.Set;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.builder.ToStringBuilder;
import org.jahia.community.modules.customgpt.CustomGptConstants;

/**
 * Serialisable container for a set of {@link CustomGptIndexOperation} objects that are passed to asynchronous executors.
 * Duplicate operations (same type + path + page ID) are silently dropped by {@link #addOperation}.
 */
public class IndexOperations implements Serializable {

    public enum CustomGptOperationType {
        NODE_INDEX, TREE_INDEX, NODE_REMOVE, NODE_MOVE, SITE_INDEX
    }

    /** A single pending indexation or removal operation for a specific JCR node path. */
    public static class CustomGptIndexOperation implements Serializable {

        private static final long serialVersionUID = -3525953451007848573L;

        CustomGptOperationType type;
        String nodePath;
        String sourcePath;
        String uuid;
        String siteKey;
        String customGptPageId;

        public CustomGptIndexOperation(CustomGptOperationType type, String nodePath, String sourcePath, String uuid, String siteKey) {
            this.type = type;
            this.nodePath = nodePath;
            this.sourcePath = sourcePath;
            this.uuid = uuid;
            this.siteKey = siteKey;
        }

        public CustomGptIndexOperation(CustomGptOperationType type, String nodePath, String sourcePath, String uuid) {
            this.type = type;
            this.nodePath = nodePath;
            this.sourcePath = sourcePath;
            this.uuid = uuid;
        }

        public CustomGptIndexOperation(CustomGptOperationType type, String nodePath, String sourcePath) {
            this.type = type;
            this.nodePath = nodePath;
            this.sourcePath = sourcePath;
        }

        public CustomGptIndexOperation(CustomGptOperationType type, String nodePath) {
            this.type = type;
            this.nodePath = nodePath;
        }

        public CustomGptIndexOperation(CustomGptOperationType type) {
            this.type = type;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            final CustomGptIndexOperation otherOp = (CustomGptIndexOperation) obj;
            return Objects.equals(type, otherOp.type) && Objects.equals(nodePath, otherOp.nodePath) && Objects.equals(customGptPageId, otherOp.customGptPageId);
        }

        public String getNodePath() {
            return nodePath;
        }

        public String getSourcePath() {
            return sourcePath;
        }

        public CustomGptOperationType getType() {
            return type;
        }

        public String getUuid() {
            return uuid;
        }

        public void setUuid(String uuid) {
            this.uuid = uuid;
        }

        public void setCustomGptPageId(String customGptPageId) {
            this.customGptPageId = customGptPageId;
        }

        public String getCustomGptPageId() {
            return customGptPageId;
        }

        public String getSiteKey() {
            if (siteKey == null) {
                return StringUtils.substringBefore(StringUtils.substringAfterLast(nodePath, CustomGptConstants.PATH_SITES), CustomGptConstants.PATH_DELIMITER);
            }

            return siteKey;
        }

        public void setSiteKey(String siteKey) {
            this.siteKey = siteKey;
        }

        @Override
        public int hashCode() {
            // Must use the SAME fields as equals(); otherwise two operations that are equal() can land in different
            // buckets and the LinkedHashSet dedup in IndexOperations silently fails to drop duplicates.
            return Objects.hash(type, nodePath, customGptPageId);
        }

        @Override
        public String toString() {
            return ToStringBuilder.reflectionToString(this);
        }
    }

    private static final long serialVersionUID = 6178926322698984907L;

    private String siteKey;
    private Set<CustomGptIndexOperation> operations = new LinkedHashSet<>();

    public IndexOperations() {
        // default constructor; operations are added later via addOperation()
    }

    public void addOperation(CustomGptIndexOperation operation) {
        // operations is a Set, so add() already dedups via equals()/hashCode().
        operations.add(operation);
    }

    public Set<CustomGptIndexOperation> getOperations() {
        return operations;
    }

    @Override
    public String toString() {
        return ToStringBuilder.reflectionToString(this);
    }

    public boolean isEmpty() {
        return operations.isEmpty();
    }

    public String getSiteKey() {
        return siteKey;
    }

    public void setSiteKey(String siteKey) {
        this.siteKey = siteKey;
    }
}
