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

    public GqlSettings(String contentIndexedMainResourceTypes, String contentIndexedSubNodeTypes,
            String contentIndexedFileExtensions, String fileMappedNodetypes, int operationsBatchSize,
            String projectId, String projectName, String token, String jahiaUsername, String jahiaPassword,
            String jahiaServerCookieName, String jahiaServerCookieValue, String jahiaServerCookieDomain,
            boolean dryRun, boolean scheduleJobASAP, String apiBaseUrl) {
        this.contentIndexedMainResourceTypes = contentIndexedMainResourceTypes;
        this.contentIndexedSubNodeTypes = contentIndexedSubNodeTypes;
        this.contentIndexedFileExtensions = contentIndexedFileExtensions;
        this.fileMappedNodetypes = fileMappedNodetypes;
        this.operationsBatchSize = operationsBatchSize;
        this.projectId = projectId;
        this.projectName = projectName;
        this.token = token;
        this.jahiaUsername = jahiaUsername;
        this.jahiaPassword = jahiaPassword;
        this.jahiaServerCookieName = jahiaServerCookieName;
        this.jahiaServerCookieValue = jahiaServerCookieValue;
        this.jahiaServerCookieDomain = jahiaServerCookieDomain;
        this.dryRun = dryRun;
        this.scheduleJobASAP = scheduleJobASAP;
        this.apiBaseUrl = apiBaseUrl;
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
}
