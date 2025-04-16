package org.jahia.community.modules.customgpt.indexer.builder;


import java.util.LinkedHashSet;
import java.util.Set;
import org.jahia.api.Constants;
import org.jahia.community.modules.customgpt.settings.Config;
import org.jahia.community.modules.customgpt.settings.NotConfiguredException;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

@Component(service = {FileIndexBuilder.class})
public class FileIndexBuilder extends AbstractIndexBuilder {

    @Activate
    public void activate(BundleContext bundleContext) {
        this.bundleContext = bundleContext;
    }

    @Override
    @Reference(service = Config.class)
    public void setCustomGptConfig(Config customGptConfig) {
        super.setCustomGptConfig(customGptConfig);
    }

    @Override
    public Set<String> getIndexedMainResourceNodeTypes() throws NotConfiguredException {
        final Set<String> mainResources = new LinkedHashSet<>();
        mainResources.add(Constants.JAHIANT_FILE);
        return mainResources;
    }
}
