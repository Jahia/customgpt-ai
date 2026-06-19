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
import org.jahia.community.modules.customgpt.util.SecurityUtils;
import org.jahia.modules.graphql.provider.dxm.DataFetchingException;
import org.jahia.osgi.BundleUtils;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRTemplate;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GraphQL mutation resolver for the {@code admin.customGpt} namespace.
 * Contains mutations for site management, indexation triggering, settings persistence,
 * and the danger-zone purge operation. All mutations enforce the {@code customGptAdmin} permission
 * on the root path, matching the field-level {@code @GraphQLRequiresPermission("customGptAdmin")}
 * annotation so the fine-grained admin role works end-to-end. Per-site operations additionally
 * enforce the site-scoped {@code site-admin} permission.
 */
@GraphQLName("CustomGptAdminMutations")
@GraphQLDescription("Generic object with admin mutation results")
public class GqlCustomGptAdminMutationResult {

    private static final Logger LOGGER = LoggerFactory.getLogger(GqlCustomGptAdminMutationResult.class);
    private static final String CUSTOM_GPT_ADMIN = "customGptAdmin";
    private static final String CONFIG_PID = "org.jahia.community.modules.customgpt";
    // Generic client-facing message; the underlying exception detail is logged server-side, never returned.
    private static final String ERR_OPERATION_FAILED = "The CustomGPT operation could not be completed";
    // Site keys are admin-supplied JCR node names; restrict to word chars and hyphen to avoid path traversal/injection.
    private static final java.util.regex.Pattern SITE_KEY_PATTERN = java.util.regex.Pattern.compile("^[\\w-]+$");

    /**
     * Returns the acting user's name, sanitised for log output (CR/LF stripped). Used to attribute privileged
     * admin mutations (purge, saveSettings, addSite, startIndex) in the server audit log.
     */
    private static String currentUserForAudit() {
        try {
            final org.jahia.services.usermanager.JahiaUser user = JCRSessionFactory.getInstance().getCurrentUser();
            return SecurityUtils.sanitizeForLog(user != null ? user.getUsername() : "<unknown>");
        } catch (RuntimeException e) {
            return "<unknown>";
        }
    }

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
    ) throws RepositoryException {
        try {
            checkAdminPermission(CustomGptConstants.PATH_DELIMITER, CUSTOM_GPT_ADMIN);
        } catch (RepositoryException e) {
            LOGGER.warn("Permission check failed for startIndex", e);
            throw new DataFetchingException(ERR_OPERATION_FAILED);
        }
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("[audit] startIndex requested by user {} for siteKeys {}", currentUserForAudit(),
                    SecurityUtils.sanitizeForLog(String.valueOf(siteKeys)));
        }
        final Service customGptService = BundleUtils.getOsgiService(Service.class, null);
        if (customGptService == null) {
            throw new DataFetchingException(new IllegalStateException("CustomGPT service is not available"));
        }
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
    ) throws RepositoryException {
        try {
            checkAdminPermission(CustomGptConstants.PATH_DELIMITER, CUSTOM_GPT_ADMIN);
        } catch (RepositoryException e) {
            LOGGER.warn("Permission check failed for startNodeIndex", e);
            throw new DataFetchingException(ERR_OPERATION_FAILED);
        }
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("[audit] startNodeIndex requested by user {} for nodePaths {}", currentUserForAudit(),
                    SecurityUtils.sanitizeForLog(String.valueOf(nodePaths)));
        }
        final GqlIndexingJob job = NodeReindexAsyncJob.triggerJob(nodePaths, inclDescendants != null && inclDescendants);
        return new GqlIndexMutationResult(Collections.singletonList(job));
    }

    private List<JobDetail> getJobDetailList(List<String> siteKeys,
            Service customGptService, boolean force) {
        final List<JobDetail> jobDetailList = new ArrayList<>();
        // No site keys supplied: trigger a full re-index of every indexable site (documented behaviour),
        // rather than dereferencing a null list.
        if (siteKeys == null) {
            customGptService.reIndexUsingJob();
            return jobDetailList;
        }
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
        // Validate the admin-supplied site key before building a JCR path to prevent path traversal / injection.
        if (siteKey == null || !SITE_KEY_PATTERN.matcher(siteKey).matches()) {
            throw new DataFetchingException(new IllegalArgumentException("Invalid site key; expected ^[\\w-]+$"));
        }
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("[audit] addSite requested by user {} for siteKey {}", currentUserForAudit(),
                    SecurityUtils.sanitizeForLog(siteKey));
        }
        try {
            checkAdminPermission(CustomGptConstants.PATH_DELIMITER, CUSTOM_GPT_ADMIN);
            return JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
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
            LOGGER.warn("addSite failed for siteKey {}", SecurityUtils.sanitizeForLog(siteKey), e);
            throw new DataFetchingException(ERR_OPERATION_FAILED);
        }
    }

    @SuppressWarnings("java:S107")
    @GraphQLField
    @GraphQLName("saveSettings")
    @GraphQLDescription("Saves the CustomGPT configuration settings")
    public Boolean saveSettings(
            @GraphQLName("contentIndexedMainResourceTypes") @GraphQLDescription("Comma-separated list of main resource node types to index") String contentIndexedMainResourceTypes,
            @GraphQLName("contentIndexedSubNodeTypes") @GraphQLDescription("Comma-separated list of sub-node types to index") String contentIndexedSubNodeTypes,
            @GraphQLName("contentIndexedFileExtensions") @GraphQLDescription("Comma-separated list of file extensions to index") String contentIndexedFileExtensions,
            @GraphQLName("operationsBatchSize") @GraphQLDescription("Batch size for bulk operations") Integer operationsBatchSize,
            @GraphQLName("projectId") @GraphQLDescription("CustomGPT project ID") String projectId,
            @GraphQLName("token") @GraphQLDescription("CustomGPT API token") String token,
            @GraphQLName("jahiaUsername") @GraphQLDescription("Jahia username for content retrieval") String jahiaUsername,
            @GraphQLName("jahiaPassword") @GraphQLDescription("Jahia password for content retrieval") String jahiaPassword,
            @GraphQLName("jahiaServerCookieName") @GraphQLDescription("Jahia server cookie name") String jahiaServerCookieName,
            @GraphQLName("jahiaServerCookieValue") @GraphQLDescription("Jahia server cookie value") String jahiaServerCookieValue,
            @GraphQLName("jahiaServerCookieDomain") @GraphQLDescription("Jahia server cookie domain") String jahiaServerCookieDomain,
            @GraphQLName("dryRun") @GraphQLDescription("Dry run mode") Boolean dryRun,
            @GraphQLName("scheduleJobASAP") @GraphQLDescription("Schedule indexing jobs immediately") Boolean scheduleJobASAP,
            @GraphQLName("apiBaseUrl") @GraphQLDescription("CustomGPT API base URL") String apiBaseUrl,
            @GraphQLName("rateLimitRequestsPerSecond") @GraphQLDescription("Maximum API requests per second (token-bucket rate limit)") Integer rateLimitRequestsPerSecond) {
        try {
            checkAdminPermission(CustomGptConstants.PATH_DELIMITER, CUSTOM_GPT_ADMIN);
        } catch (RepositoryException e) {
            LOGGER.warn("Permission check failed for saveSettings", e);
            throw new DataFetchingException(ERR_OPERATION_FAILED);
        }
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("[audit] saveSettings requested by user {}", currentUserForAudit());
        }
        // The base URL travels with the Bearer token on every API call; reject anything that is not https to
        // prevent the token from being exfiltrated over cleartext or to a non-HTTP scheme.
        if (apiBaseUrl != null && !apiBaseUrl.isEmpty() && !SecurityUtils.isHttpsUrl(apiBaseUrl)) {
            throw new DataFetchingException(new IllegalArgumentException("apiBaseUrl must be a valid https:// URL"));
        }
        try {
            final ConfigurationAdmin configAdmin = BundleUtils.getOsgiService(ConfigurationAdmin.class, null);
            if (configAdmin == null) {
                return Boolean.FALSE;
            }
            final Configuration config = configAdmin.getConfiguration(CONFIG_PID, null);
            java.util.Dictionary<String, Object> props = config.getProperties();
            if (props == null) {
                props = new java.util.Hashtable<>();
            }
            putIfNotNull(props, "org.jahia.community.modules.customgpt.content.indexedMainResourceTypes", contentIndexedMainResourceTypes);
            putIfNotNull(props, "org.jahia.community.modules.customgpt.content.indexedSubNodeTypes", contentIndexedSubNodeTypes);
            putIfNotNull(props, "org.jahia.community.modules.customgpt.content.indexedFileExtensions", contentIndexedFileExtensions);
            if (operationsBatchSize != null) {
                props.put("org.jahia.community.modules.customgpt.operations.batch.size", operationsBatchSize);
            }
            putIfNotNull(props, "org.jahia.community.modules.customgpt.projectId", projectId);
            // Secrets: persist only when the admin actually supplied a new value. A blank or the masking
            // placeholder echoed back from the settings query leaves the stored secret untouched.
            putSecretIfChanged(props, "org.jahia.community.modules.customgpt.token", token);
            putIfNotNull(props, "org.jahia.community.modules.customgpt.jahia.username", jahiaUsername);
            putSecretIfChanged(props, "org.jahia.community.modules.customgpt.jahia.password", jahiaPassword);
            putIfNotNull(props, "org.jahia.community.modules.customgpt.jahia.serverCookie.name", jahiaServerCookieName);
            putSecretIfChanged(props, "org.jahia.community.modules.customgpt.jahia.serverCookie.value", jahiaServerCookieValue);
            putIfNotNull(props, "org.jahia.community.modules.customgpt.jahia.serverCookie.domain", jahiaServerCookieDomain);
            if (dryRun != null) {
                props.put("org.jahia.community.modules.customgpt.dryRun", dryRun);
            }
            if (scheduleJobASAP != null) {
                props.put("org.jahia.community.modules.customgpt.scheduleJobASAP", scheduleJobASAP);
            }
            putIfNotNull(props, "org.jahia.community.modules.customgpt.apiBaseUrl", apiBaseUrl);
            if (rateLimitRequestsPerSecond != null) {
                props.put("org.jahia.community.modules.customgpt.rateLimit.requestsPerSecond", rateLimitRequestsPerSecond);
            }
            config.update(props);
            return Boolean.TRUE;
        } catch (java.io.IOException e) {
            LOGGER.error("Failed to save CustomGPT settings", e);
            return Boolean.FALSE;
        }
    }

    @GraphQLField
    @GraphQLName("purgeAllPages")
    @GraphQLDescription("Delete all pages from the CustomGPT project and return the number of pages deleted")
    public Integer purgeAllPages() {
        try {
            checkAdminPermission(CustomGptConstants.PATH_DELIMITER, CUSTOM_GPT_ADMIN);
        } catch (RepositoryException e) {
            LOGGER.warn("Permission check failed for purgeAllPages", e);
            throw new DataFetchingException(ERR_OPERATION_FAILED);
        }
        if (LOGGER.isWarnEnabled()) {
            LOGGER.warn("[audit] purgeAllPages (danger zone) requested by user {}", currentUserForAudit());
        }
        final Service customGptService = BundleUtils.getOsgiService(Service.class, null);
        if (customGptService == null) {
            throw new DataFetchingException(new IllegalStateException("CustomGPT service is not available"));
        }
        try {
            return customGptService.purgeAllPages();
        } catch (java.io.IOException e) {
            LOGGER.error("purgeAllPages failed", e);
            throw new DataFetchingException(ERR_OPERATION_FAILED);
        }
    }

    private void putIfNotNull(java.util.Dictionary<String, Object> props, String key, String value) {
        if (value != null) {
            props.put(key, value);
        }
    }

    /**
     * Persists a secret unless the caller did not really change it. {@code null} (field omitted) and the masking
     * placeholder (see {@link org.jahia.community.modules.customgpt.util.SecurityUtils#SECRET_PLACEHOLDER}, echoed
     * back unchanged by the UI) both leave the stored secret untouched. An explicit empty string is persisted so a
     * secret can still be cleared.
     */
    private void putSecretIfChanged(java.util.Dictionary<String, Object> props, String key, String value) {
        if (value != null && !SecurityUtils.isMaskedPlaceholder(value)) {
            props.put(key, value);
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
