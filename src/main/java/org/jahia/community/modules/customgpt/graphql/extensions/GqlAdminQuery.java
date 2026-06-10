package org.jahia.community.modules.customgpt.graphql.extensions;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLTypeExtension;
import org.jahia.community.modules.customgpt.graphql.extensions.query.AdminQueries;
import org.jahia.modules.graphql.provider.dxm.security.GraphQLRequiresPermission;

/**
 * GraphQL type extension that injects the {@code customGpt} query field under {@code admin}.
 * All read-only queries are delegated to {@link AdminQueries}.
 */
@GraphQLTypeExtension(org.jahia.modules.graphql.provider.dxm.admin.GqlAdminQuery.class)
public final class GqlAdminQuery {

    private GqlAdminQuery() {
    }

    @GraphQLField
    @GraphQLName("customGpt")
    @GraphQLDescription("CustomGPT administrative queries")
    @GraphQLRequiresPermission("customGptAdmin")
    public static AdminQueries customGpt() {
        return new AdminQueries();
    }
}
