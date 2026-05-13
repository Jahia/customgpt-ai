package org.jahia.community.modules.customgpt.graphql.extensions;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLTypeExtension;
import org.jahia.community.modules.customgpt.graphql.extensions.models.GqlCustomGptAdminMutationResult;
import org.jahia.modules.graphql.provider.dxm.admin.GqlAdminMutation;


/**
 * GraphQL type extension that injects the {@code customGpt} mutation field under {@code admin}.
 * All write operations are delegated to {@link GqlCustomGptAdminMutationResult}.
 */
@GraphQLTypeExtension(GqlAdminMutation.class)
public final class GqlCustomGptAdminMutation {

    private GqlCustomGptAdminMutation() {
    }

    @GraphQLField
    @GraphQLName("customGpt")
    @GraphQLDescription("CustomGPT administrative mutations")
    public static GqlCustomGptAdminMutationResult customGpt() {
        return new GqlCustomGptAdminMutationResult();
    }
}
