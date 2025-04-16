package org.jahia.community.modules.customgpt.graphql.extensions.models;

import graphql.annotations.annotationTypes.*;
import java.util.*;
import java.util.function.Supplier;
import javax.jcr.AccessDeniedException;
import javax.jcr.RepositoryException;
import org.jahia.community.modules.customgpt.CustomGptConstants;
import org.jahia.community.modules.customgpt.indexer.NodeReindexAsyncJob;
import org.jahia.community.modules.customgpt.service.Service;
import org.jahia.community.modules.customgpt.service.models.Site;
import org.jahia.modules.graphql.provider.dxm.DataFetchingException;
import org.jahia.osgi.BundleUtils;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRTemplate;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;

@GraphQLName("CustomGptAdminMutations")
@GraphQLDescription("Generic object with admin mutation results")
public class GqlCustomGptAdminMutationResult {

    private static final String ADMIN = "admin";

    @GraphQLName("Status")
    @GraphQLDescription("Status of the site added successfully to customGPT or already added")
    public enum Status {
        @GraphQLDescription("Successful operation")
        SUCCESSFUL("Successful"),
        @GraphQLDescription("Operation did not change anything as data was already same.")
        EXISTALREADY("Exist already"),
        @GraphQLDescription("Operation did not change anything as data was already same.")
        REMOVEDALREADY("Removed already or never existed.");

        private final String value;

        Status(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    public static class DefaultForce implements Supplier<Object> {

        @Override
        public Object get() {
            return false;
        }
    }

    @GraphQLField
    @GraphQLName("startIndex")
    @GraphQLDescription("Given a list of sites, trigger bulk indexing. If no sites are provided, triggers a full reindex.")
    public GqlIndexMutationResult startIndex(
            @GraphQLName("siteKeys") @GraphQLDescription("List of siteKeys to index") List<String> siteKeys,
            @GraphQLName("force")
            @GraphQLDescription("Force start indexation for when job has already been started")
            @GraphQLDefaultValue(DefaultForce.class) boolean force
    ) throws Exception {
        final Service customGptService = BundleUtils.getOsgiService(Service.class, null);
        if (siteKeys != null) {
            final Collection<Site> sites = customGptService.getIndexedSites().values();
            for (String siteKey : siteKeys) {
                if (sites.stream().noneMatch(site -> siteKey.equals(site.getSiteKey()))) {
                    throw new DataFetchingException(new RepositoryException("Site [" + siteKey + "] is not indexable. You need to add it "
                            + "in the customGPT manager to make it indexable"));
                }
            }
        }
        final List<JobDetail> jobDetailList = getJobDetailList(siteKeys, customGptService, force);

        return new GqlIndexMutationResult(transformJobDetailsToIndexingJobs(jobDetailList));
    }

    @GraphQLField
    @GraphQLName("startNodeIndex")
    @GraphQLDescription("Given a list of nodes, trigger bulk indexing of those nodes and its descendants.")
    public GqlIndexMutationResult startNodeIndex(
            @GraphQLName("nodePaths")
            @GraphQLDescription("List of node paths to index") List<String> nodePaths,
            @GraphQLName("inclDescendants")
            @GraphQLDescription("Re-index descendants (Optional; default=false)") Boolean inclDescendants
    ) throws Exception {
        final GqlIndexingJob job = NodeReindexAsyncJob.triggerJob(nodePaths, inclDescendants != null && inclDescendants);
        return new GqlIndexMutationResult(Collections.singletonList(job));
    }

    private List<JobDetail> getJobDetailList(List<String> siteKeys,
            Service customGptService, boolean force) {
        final List<JobDetail> jobDetailList = new ArrayList<>();
        try {
            for (String siteKey : siteKeys) {
                checkAdminPermission(CustomGptConstants.PATH_SITES + siteKey, CustomGptConstants.PERM_SITE_ADMIN);
                jobDetailList.add(customGptService.reIndexUsingJob(siteKey, force));
            }
        } catch (RepositoryException e) {
            throw new DataFetchingException(e);
        }
        return jobDetailList;
    }

    private void checkAdminPermission(String path, String permission) throws RepositoryException {
        if (!JCRSessionFactory.getInstance().getCurrentUserSession().getNode(path).hasPermission(permission)) {
            throw new AccessDeniedException(permission);
        }
    }

    @GraphQLField
    @GraphQLName("addSite")
    @GraphQLDescription("Add site to the list of indexed sites")
    public String addSite(@GraphQLName(CustomGptConstants.PROP_SITE_KEY) @GraphQLNonNull @GraphQLDescription("Site key") String siteKey) {
        try {
            return JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
                if ("".equals(siteKey)) {
                    throw new RepositoryException("Site '/sites/' does not exist. Provide a valid site key.");
                }

                final String path = CustomGptConstants.PATH_SITES + siteKey;
                checkAdminPermission(path, CustomGptConstants.PERM_SITE_ADMIN);
                final JCRNodeWrapper site = session.getNode(path);
                if (!site.isNodeType(CustomGptConstants.MIX_INDEXABLE_SITE)) {
                    site.addMixin(CustomGptConstants.MIX_INDEXABLE_SITE);
                    session.save();
                    return Status.SUCCESSFUL.getValue();
                }
                return Status.EXISTALREADY.getValue();
            });
        } catch (RepositoryException e) {
            throw new DataFetchingException(e);
        }
    }

    private List<GqlIndexingJob> transformJobDetailsToIndexingJobs(List<JobDetail> jobDetails) {
        final List<GqlIndexingJob> indexingJobs = new ArrayList<>();
        jobDetails.forEach(jobDetail -> {
            final JobDataMap jobDataMap = jobDetail.getJobDataMap();
            final GqlProjectInfo info = new GqlProjectInfo(jobDataMap.get(CustomGptConstants.PROP_SITE_KEY) != null ? jobDataMap.get(CustomGptConstants.PROP_SITE_KEY).toString() : "all sites");
            indexingJobs.add(new GqlIndexingJob(jobDetail.getName(), info, GqlIndexingJob.JobStatus.STARTED));
        });
        return indexingJobs;
    }

}
