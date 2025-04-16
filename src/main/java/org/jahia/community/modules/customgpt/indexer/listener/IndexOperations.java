package org.jahia.community.modules.customgpt.indexer.listener;

import com.google.gwt.thirdparty.guava.common.base.Objects;
import java.io.Serializable;
import java.util.LinkedHashSet;
import java.util.Set;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.builder.ToStringBuilder;
import org.jahia.community.modules.customgpt.CustomGptConstants;

public class IndexOperations implements Serializable {

    public enum CustomGptOperationType {
        NODE_INDEX, TREE_INDEX, NODE_REMOVE, NODE_MOVE, SITE_INDEX
    }

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
            return Objects.equal(type, otherOp.type) && Objects.equal(nodePath, otherOp.nodePath) && Objects.equal(customGptPageId, otherOp.customGptPageId);
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
            return Objects.hashCode(type, nodePath, sourcePath, siteKey, customGptPageId);
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
    }

    public void addOperation(CustomGptIndexOperation operation) {
        if (!operations.contains(operation)) {
            operations.add(operation);
        }
    }

    public Set<CustomGptIndexOperation> getOperations() {
        return operations;
    }

    @Override
    public String toString() {
        return ToStringBuilder.reflectionToString(this);
    }

    public boolean isEmpty() {
        return operations == null || operations.isEmpty();
    }

    public String getSiteKey() {
        return siteKey;
    }

    public void setSiteKey(String siteKey) {
        this.siteKey = siteKey;
    }
}
