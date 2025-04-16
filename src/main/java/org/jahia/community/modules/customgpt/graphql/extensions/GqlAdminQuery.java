package org.jahia.community.modules.customgpt.graphql.extensions;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLTypeExtension;
import org.jahia.community.modules.customgpt.graphql.extensions.query.AdminQueries;

@GraphQLTypeExtension(GqlAdminQuery.class)
public final class GqlAdminQuery {

    private GqlAdminQuery() {
    }

    @GraphQLField
    @GraphQLName("customGpt")
    @GraphQLDescription("CustomGPT administrative queries")
    public static AdminQueries customGpt() {
        return new AdminQueries();
    }
}
