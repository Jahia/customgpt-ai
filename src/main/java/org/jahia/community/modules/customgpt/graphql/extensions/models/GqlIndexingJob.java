package org.jahia.community.modules.customgpt.graphql.extensions.models;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;

@GraphQLName("indexJob")
@GraphQLDescription("Information related to specific indexing job")
public class GqlIndexingJob {

    @GraphQLName("jobStatus")
    @GraphQLDescription("Status of job")
    public enum JobStatus {
        @GraphQLDescription("Job has started")
        STARTED,
        @GraphQLDescription("Job has completed")
        COMPLETED
    }

    private final String id;
    private final GqlProjectInfo project;
    private final JobStatus status;

    public GqlIndexingJob(String id, GqlProjectInfo project, JobStatus status) {
        this.id = id;
        this.project = project;
        this.status = status;
    }

    @GraphQLField
    @GraphQLDescription("The ID of the job that was just started")
    public String getId() {
        return id;
    }

    @GraphQLField
    @GraphQLDescription("Relation to a Jahia project object")
    public GqlProjectInfo getProject() {
        return project;
    }

    @GraphQLField
    @GraphQLDescription("Indexing job status")
    public JobStatus getStatus() {
        return status;
    }
}
