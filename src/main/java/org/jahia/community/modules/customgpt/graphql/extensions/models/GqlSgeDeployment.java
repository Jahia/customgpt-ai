package org.jahia.community.modules.customgpt.graphql.extensions.models;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;

/**
 * What one node makes of the Search Generative Experience deployment.
 *
 * <p>Exists because the assistant's absence was undiagnosable on a cluster. The button is emitted by a JSP
 * that asks this module during a page render, so the only way to see a node's answer was to load a page on
 * that node and infer the answer from whether a button appeared -- through the HTML cache, with four
 * distinct faults all rendering as the same nothing. This reports the answer directly, from whichever node
 * handles the query, with the reason attached.
 *
 * <p>It carries no secret. {@code keyPresent} says whether a deployment key was obtained, never its value,
 * and the API token appears nowhere.
 */
@GraphQLName("CustomGptSgeDeployment")
@GraphQLDescription("The assistant deployment as resolved by the node answering this query")
public class GqlSgeDeployment {

    private final String node;
    private final boolean processingServer;
    private final String projectId;
    private final boolean keyPresent;
    private final boolean available;
    private final boolean usable;
    private final String reason;
    private final long elapsedMs;

    public GqlSgeDeployment(String node, boolean processingServer, String projectId, boolean keyPresent,
            boolean available, boolean usable, String reason, long elapsedMs) {
        this.node = node;
        this.processingServer = processingServer;
        this.projectId = projectId;
        this.keyPresent = keyPresent;
        this.available = available;
        this.usable = usable;
        this.reason = reason;
        this.elapsedMs = elapsedMs;
    }

    @GraphQLField
    @GraphQLDescription("Host name of the node that answered, so the reply cannot be attributed to the wrong one")
    public String getNode() {
        return node;
    }

    @GraphQLField
    @GraphQLDescription("Whether that node is the processing server")
    public boolean isProcessingServer() {
        return processingServer;
    }

    @GraphQLField
    @GraphQLDescription("Project id in THIS node's configuration; empty means the node has none")
    public String getProjectId() {
        return projectId;
    }

    @GraphQLField
    @GraphQLDescription("Whether a public deployment key was obtained. The key itself is never returned here")
    public boolean isKeyPresent() {
        return keyPresent;
    }

    @GraphQLField
    @GraphQLDescription("False only when the query quota for the billing cycle is known to be spent")
    public boolean isAvailable() {
        return available;
    }

    @GraphQLField
    @GraphQLDescription("The single question the page asks: should the assistant be offered on this node")
    public boolean isUsable() {
        return usable;
    }

    @GraphQLField
    @GraphQLDescription("RESOLVED, QUOTA_SPENT, NO_PROJECT_ID, NO_HTTP_CLIENT, BAD_API_BASE_URL or NO_DEPLOYMENT_KEY")
    public String getReason() {
        return reason;
    }

    @GraphQLField
    @GraphQLDescription("How long the resolution took on this node, in ms. Telling when a call is timing out")
    public long getElapsedMs() {
        return elapsedMs;
    }
}
