package org.jahia.community.modules.customgpt.graphql.extensions.models;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;

@GraphQLName("project")
@GraphQLDescription("Site specific information ")
public class GqlProjectInfo {

    private final String siteKey;

    public GqlProjectInfo(String siteKey) {
        this.siteKey = siteKey;
    }

    @GraphQLField
    @GraphQLDescription("Unique site identifier")
    public String getSiteKey() {
        return siteKey;
    }
}
