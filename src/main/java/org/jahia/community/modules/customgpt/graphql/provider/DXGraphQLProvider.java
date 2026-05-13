package org.jahia.community.modules.customgpt.graphql.provider;

import java.util.Arrays;
import java.util.Collection;
import org.jahia.community.modules.customgpt.graphql.extensions.GqlAdminQuery;
import org.jahia.community.modules.customgpt.graphql.extensions.GqlCustomGptAdminMutation;
import org.jahia.modules.graphql.provider.dxm.DXGraphQLExtensionsProvider;
import org.osgi.service.component.annotations.Component;

/**
 * Registers the CustomGPT GraphQL type extensions ({@link GqlAdminQuery} and {@link GqlCustomGptAdminMutation})
 * with the {@code graphql-dxm-provider} extension mechanism.
 */
@Component(immediate = true, service = DXGraphQLExtensionsProvider.class)
public class DXGraphQLProvider implements DXGraphQLExtensionsProvider {

    @Override
    public Collection<Class<?>> getExtensions() {
        return Arrays.asList(GqlAdminQuery.class, GqlCustomGptAdminMutation.class);
    }
}
