package org.jahia.community.modules.customgpt.indexer.builder;

import org.jahia.community.modules.customgpt.settings.Config;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

@Component(service = {ContentIndexBuilder.class})
public class ContentIndexBuilder extends AbstractIndexBuilder {

    @Activate
    public void activate(BundleContext bundleContext) {
        this.bundleContext = bundleContext;
    }

    @Reference(service = Config.class)
    public void setCustomGptConfig(Config customGptConfig) {
        super.setCustomGptConfig(customGptConfig);
    }
}
