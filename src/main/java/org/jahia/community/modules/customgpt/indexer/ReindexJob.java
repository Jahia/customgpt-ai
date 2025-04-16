package org.jahia.community.modules.customgpt.indexer;

import org.jahia.community.modules.customgpt.CustomGptConstants;
import org.jahia.community.modules.customgpt.indexer.listener.IndexOperations;
import org.jahia.community.modules.customgpt.service.Service;
import org.jahia.community.modules.customgpt.settings.Config;
import org.jahia.data.templates.JahiaTemplatesPackage;
import org.jahia.exceptions.JahiaException;
import org.jahia.osgi.BundleUtils;
import org.jahia.registries.ServicesRegistry;
import org.jahia.services.scheduler.BackgroundJob;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ReindexJob extends BackgroundJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReindexJob.class);

    @Override
    public void executeJahiaJob(JobExecutionContext jobExecutionContext) throws Exception {
        final String siteKey = jobExecutionContext.getJobDetail().getJobDataMap().getString(CustomGptConstants.PROP_SITE_KEY);
        LOGGER.info("Starting Site {} indexation job in workspace live, injecting operation into CustomGptService", siteKey);
        final JahiaTemplatesPackage module = ServicesRegistry.getInstance().getJahiaTemplateManagerService().getTemplatePackageById(CustomGptConstants.CUSTOM_GPT_MODULE_NAME);
        if (module == null) {
            LOGGER.error("Cannot find module %s, indexation of {} cancelled", CustomGptConstants.CUSTOM_GPT_MODULE_NAME, siteKey);
            return;
        }
        final Service customGptService = BundleUtils.getOsgiService(Service.class, null);
        if (customGptService == null) {
            LOGGER.error("Indexation of site {} can not be executed as we did not found customGptService using the OSGI framework", siteKey);
            throw new JahiaException("Indexation of site " + siteKey + " can not be executed as we did not found customGptService using the OSGI framework", "No CustomGptService found", JahiaException.SERVICE_ERROR, JahiaException.ERROR_SEVERITY);
        }
        final Config customGptConfig = BundleUtils.getOsgiService(Config.class, null);
        if (customGptConfig == null) {
            LOGGER.error("Indexation of site {} can not be executed as we did not found customGptConfig using the OSGI framework", siteKey);
            throw new JahiaException("Indexation of site " + siteKey + " can not be executed as we did not found customGptConfig using the OSGI framework", "No CustomGptConfig found", JahiaException.SERVICE_ERROR, JahiaException.ERROR_SEVERITY);
        }
        final String sitePath = CustomGptConstants.PATH_SITES + siteKey;
        final IndexOperations liveOperations = new IndexOperations();
        liveOperations.addOperation(new IndexOperations.CustomGptIndexOperation(IndexOperations.CustomGptOperationType.SITE_INDEX, sitePath));
        customGptService.produceSiteAsynchronousIndexations(sitePath, liveOperations);
    }
}
