package org.jahia.community.modules.customgpt.graphql.extensions.models;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@GraphQLName("indexMutationResult")
@GraphQLDescription("Information about indexing jobs")
public class GqlIndexMutationResult {

    private List<GqlIndexingJob> jobs;

    public GqlIndexMutationResult(List<GqlIndexingJob> jobs) {
        if (jobs != null) {
            this.jobs = Collections.unmodifiableList(jobs);
        }
    }

    @GraphQLField
    @GraphQLDescription("Information about indexing jobs, one per site indexed")
    public List<GqlIndexingJob> getJobs() {
        return jobs == null ? null : new ArrayList<>(jobs);
    }
}
