package org.jahia.community.modules.customgpt.graphql.extensions.models;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;

@GraphQLName("CustomGptSettings")
@GraphQLDescription("CustomGPT configuration settings")
public class GqlSettings {

    private final String contentIndexedMainResourceTypes;
    private final String contentIndexedSubNodeTypes;
    private final String contentIndexedFileExtensions;
    private final String fileMappedNodetypes;
    private final int operationsBatchSize;
    private final String projectId;
    private final String projectName;
    private final String token;
    private final String jahiaUsername;
    private final String jahiaPassword;
    private final String jahiaServerCookieName;
    private final String jahiaServerCookieValue;
    private final String jahiaServerCookieDomain;
    private final boolean dryRun;
    private final boolean scheduleJobASAP;
    private final String apiBaseUrl;
    private final int rateLimitRequestsPerSecond;

    private GqlSettings(Builder b) {
        this.contentIndexedMainResourceTypes = b.contentIndexedMainResourceTypes;
        this.contentIndexedSubNodeTypes = b.contentIndexedSubNodeTypes;
        this.contentIndexedFileExtensions = b.contentIndexedFileExtensions;
        this.fileMappedNodetypes = b.fileMappedNodetypes;
        this.operationsBatchSize = b.operationsBatchSize;
        this.projectId = b.projectId;
        this.projectName = b.projectName;
        this.token = b.token;
        this.jahiaUsername = b.jahiaUsername;
        this.jahiaPassword = b.jahiaPassword;
        this.jahiaServerCookieName = b.jahiaServerCookieName;
        this.jahiaServerCookieValue = b.jahiaServerCookieValue;
        this.jahiaServerCookieDomain = b.jahiaServerCookieDomain;
        this.dryRun = b.dryRun;
        this.scheduleJobASAP = b.scheduleJobASAP;
        this.apiBaseUrl = b.apiBaseUrl;
        this.rateLimitRequestsPerSecond = b.rateLimitRequestsPerSecond;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String contentIndexedMainResourceTypes;
        private String contentIndexedSubNodeTypes;
        private String contentIndexedFileExtensions;
        private String fileMappedNodetypes;
        private int operationsBatchSize;
        private String projectId;
        private String projectName;
        private String token;
        private String jahiaUsername;
        private String jahiaPassword;
        private String jahiaServerCookieName;
        private String jahiaServerCookieValue;
        private String jahiaServerCookieDomain;
        private boolean dryRun;
        private boolean scheduleJobASAP;
        private String apiBaseUrl;
        private int rateLimitRequestsPerSecond;

        public Builder contentIndexedMainResourceTypes(String v) { this.contentIndexedMainResourceTypes = v; return this; }
        public Builder contentIndexedSubNodeTypes(String v) { this.contentIndexedSubNodeTypes = v; return this; }
        public Builder contentIndexedFileExtensions(String v) { this.contentIndexedFileExtensions = v; return this; }
        public Builder fileMappedNodetypes(String v) { this.fileMappedNodetypes = v; return this; }
        public Builder operationsBatchSize(int v) { this.operationsBatchSize = v; return this; }
        public Builder projectId(String v) { this.projectId = v; return this; }
        public Builder projectName(String v) { this.projectName = v; return this; }
        public Builder token(String v) { this.token = v; return this; }
        public Builder jahiaUsername(String v) { this.jahiaUsername = v; return this; }
        public Builder jahiaPassword(String v) { this.jahiaPassword = v; return this; }
        public Builder jahiaServerCookieName(String v) { this.jahiaServerCookieName = v; return this; }
        public Builder jahiaServerCookieValue(String v) { this.jahiaServerCookieValue = v; return this; }
        public Builder jahiaServerCookieDomain(String v) { this.jahiaServerCookieDomain = v; return this; }
        public Builder dryRun(boolean v) { this.dryRun = v; return this; }
        public Builder scheduleJobASAP(boolean v) { this.scheduleJobASAP = v; return this; }
        public Builder apiBaseUrl(String v) { this.apiBaseUrl = v; return this; }
        public Builder rateLimitRequestsPerSecond(int v) { this.rateLimitRequestsPerSecond = v; return this; }

        public GqlSettings build() { return new GqlSettings(this); }
    }

    @GraphQLField
    @GraphQLName("contentIndexedMainResourceTypes")
    @GraphQLDescription("Comma-separated list of main resource node types to index")
    public String getContentIndexedMainResourceTypes() {
        return contentIndexedMainResourceTypes;
    }

    @GraphQLField
    @GraphQLName("contentIndexedSubNodeTypes")
    @GraphQLDescription("Comma-separated list of sub-node types to index")
    public String getContentIndexedSubNodeTypes() {
        return contentIndexedSubNodeTypes;
    }

    @GraphQLField
    @GraphQLName("contentIndexedFileExtensions")
    @GraphQLDescription("Comma-separated list of file extensions to index")
    public String getContentIndexedFileExtensions() {
        return contentIndexedFileExtensions;
    }

    @GraphQLField
    @GraphQLName("fileMappedNodetypes")
    @GraphQLDescription("Comma-separated list of file mapped node types")
    public String getFileMappedNodetypes() {
        return fileMappedNodetypes;
    }

    @GraphQLField
    @GraphQLName("operationsBatchSize")
    @GraphQLDescription("Batch size for bulk operations")
    public int getOperationsBatchSize() {
        return operationsBatchSize;
    }

    @GraphQLField
    @GraphQLName("projectId")
    @GraphQLDescription("CustomGPT project ID")
    public String getProjectId() {
        return projectId;
    }

    @GraphQLField
    @GraphQLName("projectName")
    @GraphQLDescription("CustomGPT project name resolved from the API")
    public String getProjectName() {
        return projectName;
    }

    @GraphQLField
    @GraphQLName("token")
    @GraphQLDescription("CustomGPT API token")
    public String getToken() {
        return token;
    }

    @GraphQLField
    @GraphQLName("jahiaUsername")
    @GraphQLDescription("Jahia username for content retrieval")
    public String getJahiaUsername() {
        return jahiaUsername;
    }

    @GraphQLField
    @GraphQLName("jahiaPassword")
    @GraphQLDescription("Jahia password for content retrieval")
    public String getJahiaPassword() {
        return jahiaPassword;
    }

    @GraphQLField
    @GraphQLName("jahiaServerCookieName")
    @GraphQLDescription("Jahia server cookie name")
    public String getJahiaServerCookieName() {
        return jahiaServerCookieName;
    }

    @GraphQLField
    @GraphQLName("jahiaServerCookieValue")
    @GraphQLDescription("Jahia server cookie value")
    public String getJahiaServerCookieValue() {
        return jahiaServerCookieValue;
    }

    @GraphQLField
    @GraphQLName("jahiaServerCookieDomain")
    @GraphQLDescription("Jahia server cookie domain")
    public String getJahiaServerCookieDomain() {
        return jahiaServerCookieDomain;
    }

    @GraphQLField
    @GraphQLName("dryRun")
    @GraphQLDescription("Dry run mode (do not actually send data to CustomGPT)")
    public boolean isDryRun() {
        return dryRun;
    }

    @GraphQLField
    @GraphQLName("scheduleJobASAP")
    @GraphQLDescription("Schedule indexing jobs immediately")
    public boolean isScheduleJobASAP() {
        return scheduleJobASAP;
    }

    @GraphQLField
    @GraphQLName("apiBaseUrl")
    @GraphQLDescription("CustomGPT API base URL")
    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    @GraphQLField
    @GraphQLName("rateLimitRequestsPerSecond")
    @GraphQLDescription("Maximum number of requests per second sent to the CustomGPT API (token-bucket rate limit)")
    public int getRateLimitRequestsPerSecond() {
        return rateLimitRequestsPerSecond;
    }
}
