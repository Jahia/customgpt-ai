package org.jahia.community.modules.customgpt.service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.query.Query;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.felix.utils.collections.MapToDictionary;
import org.jahia.api.Constants;
import org.jahia.api.settings.SettingsBean;
import org.jahia.api.templates.JahiaTemplateManagerService;
import org.jahia.community.modules.customgpt.CustomGptConstants;
import org.jahia.community.modules.customgpt.CustomGptRequest;
import org.jahia.community.modules.customgpt.indexer.Indexer;
import org.jahia.community.modules.customgpt.indexer.ReindexJob;
import org.jahia.community.modules.customgpt.indexer.builder.ContentIndexBuilder;
import org.jahia.community.modules.customgpt.indexer.builder.FileIndexBuilder;
import org.jahia.community.modules.customgpt.indexer.listener.IndexOperations;
import org.jahia.community.modules.customgpt.indexer.listener.IndexOperations.CustomGptOperationType;
import org.jahia.community.modules.customgpt.indexer.listener.IndexerJCRListener;
import org.jahia.community.modules.customgpt.service.models.Site;
import org.jahia.community.modules.customgpt.settings.Config;
import org.jahia.community.modules.customgpt.settings.NotConfiguredException;
import org.jahia.community.modules.customgpt.util.RateLimitInterceptor;
import org.jahia.community.modules.customgpt.util.SecurityUtils;
import org.jahia.osgi.FrameworkService;
import org.jahia.services.content.*;
import org.jahia.services.events.JournalEventReader;
import org.jahia.services.query.QueryWrapper;
import org.jahia.services.scheduler.BackgroundJob;
import org.jahia.services.scheduler.SchedulerService;
import org.jahia.community.modules.customgpt.indexer.OrphanedPages;
import org.jahia.community.modules.customgpt.indexer.PageUrlRepair;
import org.jahia.services.usermanager.JahiaUser;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.SchedulerException;
import org.quartz.SimpleTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central OSGi component that orchestrates CustomGPT.ai integration.
 * Responsibilities: managing OkHttp3 clients (with Bearer auth and rate-limit jitter),
 * registering the JCR live-workspace listener, scheduling Quartz re-indexation jobs,
 * and handling OSGi events from {@link CustomGptConstants#EVENT_TOPIC}.
 */
@Component(service = {Service.class}, immediate = true)
public class Service implements EventHandler {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(Service.class);
    private static final Pattern SITE_MATCHER = Pattern.compile("\\/sites\\/.+");
    /** A site key must be a single safe path segment; it is interpolated into JCR paths and queries. */
    private static final Pattern SITE_KEY_PATTERN = Pattern.compile("^[\\w-]+$");
    private static final String ADDED_TO_THE_REGISTRY = "Task {}{} is added to the registry";
    private static final String CUSTOM_GPT_SITE_INDEXATION = "CustomGpt site indexation";
    private static final String REGISTER_EVENT = "org/jahia/modules/sam/TaskRegistryService/REGISTER";
    private static final String REMOVED_FROM_REGISTRY = "Task {} {} is removed from registry";
    private static final String UNREGISTER_EVENT = "org/jahia/modules/sam/TaskRegistryService/UNREGISTER";
    private static final String INDEXATION_FAILED_DUE_TO_CONFIGURATION_ISSUES = "Indexation failed due to configuration issues: {}";
    private static final String PROP_INDEXATION_END = "customGptIndexationEnd";
    private static final String PROP_INDEXATION_FAILED = "customGptIndexationFailed";
    private static final String PROP_INDEXATION_SCHEDULED = "customGptIndexationScheduled";
    private static final String PROP_INDEXATION_START = "customGptIndexationStart";
    private static final String RECREATE_LOG = "Recreate Log";
    private static final int N_THREADS = 2;
    private static final int DEFAULT_BATCH_SIZE = 10;
    // OkHttp timeouts: bound every outbound CustomGPT/Jahia call so a stalled peer cannot pin a worker thread forever.
    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int READ_TIMEOUT_SECONDS = 30;
    private static final int WRITE_TIMEOUT_SECONDS = 30;
    private static final int CALL_TIMEOUT_SECONDS = 60;
    // Executor shutdown budget. Deliberately >= CALL_TIMEOUT_SECONDS: indexing a node is a DELETE of the previous
    // CustomGPT page followed by a re-add (see CustomGptIndexerNodeHandler), so a worker interrupted mid-sequence
    // leaves a page removed from the customer's project and never recreated. A budget shorter than one call
    // timeout makes that the normal outcome of a module update rather than a rare one. Deactivation is slower as
    // a result; losing customer content is worse.
    private static final int POOL_GRACEFUL_SHUTDOWN_SECONDS = 60;
    private static final int POOL_FORCED_SHUTDOWN_SECONDS = 60;
    private static final String HEADER_AUTHORIZATION = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String HEADER_ACCEPT = "accept";
    private static final String MEDIA_TYPE_JSON = "application/json";

    /**
     * How long a resolved SGE deployment is reused before the quota is checked again.
     *
     * <p>Caching is not an optimisation here. Without it every render of a search page would make two
     * authenticated calls to CustomGPT, one of them to an endpoint that is itself rate limited, which would
     * turn page views into API spend and eventually into 429s. Five minutes is chosen against what the data
     * does: a team's query quota moves slowly, and the cost of being up to five minutes stale is a handful
     * of visitors seeing the widget just after the quota ran out, who then see CustomGPT's own message.
     */
    private static final long SGE_CACHE_TTL_MS = 5L * 60L * 1000L;

    private final AtomicReference<CachedSgeDeployment> sgeCache = new AtomicReference<>();
    private static final String UNSET = "unset";
    private BundleContext bundleContext;
    private Config customGptConfig;
    private IndexerJCRListener jcrListenerLive;
    private IndexService indexService;
    private JahiaTemplateManagerService jahiaTemplateManagerService;
    private JournalEventReader journalEventReader;
    private SchedulerService schedulerService;
    private ServiceRegistration<EventHandler> eventHandlerServiceRegistration;
    private SettingsBean settingsBean;
    private String journalEventReaderKey;
    private volatile boolean initialized;
    /**
     * Terminal latch set at the top of {@link #stop()}. {@code initialized} is cleared by {@code stop()}, so on its
     * own it cannot distinguish "not started yet" from "already shut down": an OSGi event still in flight when the
     * component is deactivated would pass the {@code !initialized} check in {@link #init()} and register a fresh
     * JCR listener from a bundle that is on its way out - stranding it exactly like the listener this class now
     * takes care to detach. Once set, this latch is never cleared; a redeployed bundle gets a new instance.
     */
    private volatile boolean disposed;
    /**
     * Nodes with an index operation queued or running, so repeats from the same publication can be dropped.
     * See {@link #produceAsynchronousOperations}.
     */
    private final Set<String> pendingOperationKeys = ConcurrentHashMap.newKeySet();
    /** Makes {@link #closeHttpClients()} idempotent; only ever touched under that method's monitor. */
    private boolean httpClientsClosed;
    private boolean journalEventReaderEnabled;
    private OkHttpClient customGptClient;
    private OkHttpClient jahiaClient;
    // Executors are restarted on demand by the synchronized restartExecutor* guards; volatile so a fresh pool
    // published by one thread is visible to the producers reading the field. The volatile reference is only ever
    // swapped inside synchronized restartExecutor* methods, so volatile (not AtomicReference) is the intended
    // design here.
    @SuppressWarnings("java:S3077")
    private volatile ExecutorService executor = Executors.newFixedThreadPool(1);
    @SuppressWarnings("java:S3077")
    private volatile ExecutorService executorFullIndexation = Executors.newFixedThreadPool(1);
    @SuppressWarnings("java:S3077")
    private volatile ExecutorService executorNThreads = Executors.newFixedThreadPool(N_THREADS);
    
    @Activate
    public void activate(BundleContext bundleContext) {
        this.bundleContext = bundleContext;
        start();
    }
    
    @Deactivate
    public void deactivate() {
        stop();
    }
    
    @Reference(service = Config.class)
    public void setCustomGptConfig(Config customGptConfig) {
        this.customGptConfig = customGptConfig;
        // A new project id or token makes the cached deployment wrong, and it would otherwise survive for
        // the rest of the TTL -- long enough for someone to change the configuration, reload, and conclude
        // the change did not take.
        invalidateSgeDeployment();
    }
    
    @Reference(service = SettingsBean.class)
    public void setSettingsBean(SettingsBean settingsBean) {
        this.settingsBean = settingsBean;
    }
    
    @Reference(service = JahiaTemplateManagerService.class)
    public void setTemplateManager(JahiaTemplateManagerService service) {
        this.jahiaTemplateManagerService = service;
    }
    
    @Reference(service = SchedulerService.class)
    public void setSchedulerService(SchedulerService schedulerService) {
        this.schedulerService = schedulerService;
    }
    
    @Reference(service = IndexService.class)
    public void setIndexService(IndexService indexService) {
        this.indexService = indexService;
        ((FileIndexBuilder) this.indexService.getIndexBuilder(CustomGptConstants.IndexType.FILE)).setCustomGptService(this);
        ((ContentIndexBuilder) this.indexService.getIndexBuilder(CustomGptConstants.IndexType.CONTENT)).setCustomGptService(this);
    }
    
    private Indexer createIndexer() {
        return new Indexer(this, customGptConfig);
    }
    
    public Set<String> getNodePathsToIndex(JCRNodeWrapper node) throws RepositoryException, NotConfiguredException {
        return indexService.getNodePathsToIndex(node);
    }
    
    public void addIndexRequests(
            JCRNodeWrapper node, String language, Set<CustomGptRequest> requests) throws RepositoryException, NotConfiguredException {
        indexService.addIndexRequests(node, language, requests);
    }
    
    public JCRNodeWrapper getParentDisplayableNode(JCRNodeWrapper nestedNode) throws NotConfiguredException {
        return indexService.getParentDisplayableNode(nestedNode);
    }
    
    public Set<String> getIndexedMainResourceNodeTypes() throws NotConfiguredException {
        return indexService.getIndexedMainResourceNodeTypes();
    }
    
    public Set<String> getIndexedSubNodeTypes() throws NotConfiguredException {
        return indexService.getIndexedSubNodeTypes();
    }
    
    /** Schedules a full re-indexation job for {@code siteKey}; equivalent to {@code reIndexUsingJob(siteKey, false)}. */
    public JobDetail reIndexUsingJob(String siteKey) {
        return reIndexUsingJob(siteKey, false);
    }

    /**
     * Creates and schedules a {@link ReindexJob} for the given site.
     * When {@code force=true} the existing start/end timestamps are cleared so a previously-running job can be re-triggered.
     * The trigger fires immediately when {@code scheduleJobASAP=true}, or 1 minute + 3 seconds from now otherwise.
     */
    public JobDetail reIndexUsingJob(String siteKey, boolean force) {
        final JobDetail reindexJobDetail = BackgroundJob.createJahiaJob(RECREATE_LOG, ReindexJob.class);
        final JobDataMap jobMap = new JobDataMap();
        jobMap.put(CustomGptConstants.PROP_SITE_KEY, siteKey);
        reindexJobDetail.setJobDataMap(jobMap);
        
        try {
            JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
                final JCRNodeWrapper jcrNodeWrapper = session.getNode(CustomGptConstants.PATH_SITES + siteKey);
                updateIndexationProperties(jcrNodeWrapper, new GregorianCalendar(), force);
                session.save();
                return null;
            });
            final SimpleTrigger trigger = getSimpleTrigger(reindexJobDetail);
            schedulerService.getScheduler().scheduleJob(reindexJobDetail, trigger);
        } catch (SchedulerException | RepositoryException e) {
            LOGGER.error("Failed to schedule indexation job for site {}: {}", siteKey, e.getMessage(), e);
        }
        return reindexJobDetail;
    }
    
    private void updateIndexationProperties(JCRNodeWrapper jcrNodeWrapper, Calendar scheduled, boolean force) throws RepositoryException {
        final boolean hasIndexationStart = jcrNodeWrapper.hasProperty(PROP_INDEXATION_START);
        final boolean hasIndexationEnd = jcrNodeWrapper.hasProperty(PROP_INDEXATION_END);

        if (!hasIndexationStart && !hasIndexationEnd) {
            jcrNodeWrapper.setProperty(Service.PROP_INDEXATION_SCHEDULED, scheduled);
            return;
        }
        final Calendar indexationStartLastRun = hasIndexationStart
                ? jcrNodeWrapper.getProperty(Service.PROP_INDEXATION_START).getDate() : null;
        if (!jcrNodeWrapper.hasProperty(Service.PROP_INDEXATION_SCHEDULED)) {
            jcrNodeWrapper.setProperty(Service.PROP_INDEXATION_SCHEDULED, scheduled);
        }
        final Calendar indexationScheduledLastRun = jcrNodeWrapper.getProperty(Service.PROP_INDEXATION_SCHEDULED).getDate();
        if (!shouldUpdateScheduled(force, hasIndexationStart, indexationScheduledLastRun, indexationStartLastRun, scheduled)) {
            return;
        }
        final Calendar indexationEndLastRun = hasIndexationEnd
                ? jcrNodeWrapper.getProperty(Service.PROP_INDEXATION_END).getDate() : null;
        if (force) {
            clearForcedTimestamps(jcrNodeWrapper, hasIndexationStart, hasIndexationEnd);
        }
        if (force || !hasIndexationStart || (indexationEndLastRun != null && scheduled.after(indexationEndLastRun) && indexationEndLastRun.after(indexationStartLastRun))) {
            jcrNodeWrapper.setProperty(Service.PROP_INDEXATION_SCHEDULED, scheduled);
        }
    }

    private static boolean shouldUpdateScheduled(boolean force, boolean hasIndexationStart,
            Calendar indexationScheduledLastRun, Calendar indexationStartLastRun, Calendar scheduled) {
        return force || !hasIndexationStart
                || (indexationScheduledLastRun.before(indexationStartLastRun) && scheduled.after(indexationStartLastRun));
    }

    private static void clearForcedTimestamps(JCRNodeWrapper jcrNodeWrapper, boolean hasIndexationStart, boolean hasIndexationEnd) throws RepositoryException {
        if (hasIndexationStart) {
            jcrNodeWrapper.getProperty(Service.PROP_INDEXATION_START).remove();
        }
        if (hasIndexationEnd) {
            jcrNodeWrapper.getProperty(Service.PROP_INDEXATION_END).remove();
        }
    }
    
    private SimpleTrigger getSimpleTrigger(JobDetail reindexJobDetail) {
        final Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.MINUTE, 1);
        calendar.set(Calendar.SECOND, 3);
        final SimpleTrigger trigger;
        if (customGptConfig.isScheduleJobASAP()) {
            trigger = new SimpleTrigger(reindexJobDetail.getName() + "Trigger", reindexJobDetail.getGroup());
        } else {
            trigger = new SimpleTrigger(reindexJobDetail.getName() + "Trigger", reindexJobDetail.getGroup(), calendar.getTime());
        }
        trigger.setMisfireInstruction(SimpleTrigger.MISFIRE_INSTRUCTION_FIRE_NOW);
        trigger.setPriority(3);
        return trigger;
    }
    
    public void reIndexUsingJob() {
        for (String site : getIndexedSites().keySet()) {
            reIndexUsingJob(site);
        }
    }
    
    public void produceAsynchronousFullIndexation(IndexOperations operations) {
        restartExecutorFullIndexation();
        CompletableFuture.runAsync(() -> {
            try {
                performIndexation(operations);
            } catch (RepositoryException | IOException e) {
                LOGGER.error("Indexation failed due to: {}", e.getMessage(), e);
            }
        }, executorFullIndexation);
    }
    
    // Guarded so concurrent producers cannot race the shutdown/terminated check and lose tasks to a dead pool.
    /**
     * Refuses to allocate a thread pool for a component that has been stopped. A new pool here would be a live
     * pool owned by a dead bundle generation, running module code on an invalidated classloader; and reaching
     * this at all means a stranded listener is still calling in, which is the JAHIACOM-1675 failure itself.
     */
    private void assertNotDisposed() {
        if (disposed) {
            throw new RejectedExecutionException("The CustomGPT service has been stopped;"
                    + " a stranded JCR listener is still submitting indexation work. This node needs restarting.");
        }
    }

    private synchronized void restartExecutor() {
        assertNotDisposed();
        if (executor.isShutdown() || executor.isTerminated()) {
            LOGGER.warn("Executor is shutdown or terminated, starting a new one");
            executor = Executors.newFixedThreadPool(1);
        }
    }

    private synchronized void restartExecutorFullIndexation() {
        assertNotDisposed();
        if (executorFullIndexation.isShutdown() || executorFullIndexation.isTerminated()) {
            LOGGER.warn("ExecutorFullIndexation is shutdown or terminated, starting a new one");
            executorFullIndexation = Executors.newFixedThreadPool(1);
        }
    }

    private synchronized void restartExecutorNThreads() {
        assertNotDisposed();
        if (executorNThreads.isShutdown() || executorNThreads.isTerminated()) {
            LOGGER.warn("Executor with {} threads is shutdown or terminated, starting a new one", N_THREADS);
            executorNThreads = Executors.newFixedThreadPool(N_THREADS);
        }
    }

    /**
     * Dispatches index operations, dropping any whose node already has an equivalent operation queued or running.
     *
     * <p>One publication is not one JCR save: {@code JCRPublicationService.publish} saves repeatedly across the
     * subtree, and each save delivers its own event batch. {@code IndexerJCRListener.onEvent} builds a fresh
     * {@code IndexOperations} per batch, so its internal de-duplication cannot see across batches and the same
     * node is dispatched several times for one publication - measured at up to 10, a few seconds apart.
     *
     * <p>Each of those repeats issues a DELETE of the previous CustomGPT page and a POST of a new one, so N
     * batches cost N times the API calls to reach the state one would have reached. That is not just waste: this
     * API degrades under sustained volume, so the redundant calls consume the very rate budget whose exhaustion
     * produces the degraded responses.
     *
     * <p>Correctness does not depend on this - with a working DELETE the repeats converge on a single page
     * regardless (see {@code CustomGptIndexerNodeHandler.removeExistingPage}). This removes the cost.
     *
     * <p>The trade-off: a genuine second change to a node, published while the first index is still pending, is
     * dropped rather than queued. Within a publication that is exactly right, since the repeats carry identical
     * content. Across two publications seconds apart it means the later content waits for the next publication.
     * Losing a few seconds of freshness is preferable to multiplying the request volume on every publish.
     */
    public void produceAsynchronousOperations(IndexOperations... operations) {
        restartExecutor();
        final List<CompletableFuture<Void>> dispatched = new ArrayList<>(operations.length);
        for (IndexOperations operation : operations) {
            final IndexOperations coalesced = dropOperationsAlreadyPending(operation);
            if (coalesced.isEmpty()) {
                continue;
            }
            try {
                dispatched.add(CompletableFuture
                        .supplyAsync(getPerformIndexationSupplier(coalesced), executor)
                        .whenComplete((unused, throwable) -> releasePending(coalesced)));
            } catch (RuntimeException e) {
                // Never leave a key behind: a node whose key is stuck pending would never be indexed again.
                releasePending(coalesced);
                throw e;
            }
        }
        if (dispatched.isEmpty()) {
            return;
        }
        CompletableFuture.allOf(dispatched.toArray(new CompletableFuture[0])).whenCompleteAsync((unused, throwable) -> {
            if (throwable != null) {
                LOGGER.error("One or more asynchronous indexation operations failed: {}", throwable.getMessage(), throwable);
            }
        });
    }

    /** Claims each operation's node, returning only those not already claimed by queued or running work. */
    private IndexOperations dropOperationsAlreadyPending(IndexOperations source) {
        final IndexOperations kept = new IndexOperations();
        kept.setSiteKey(source.getSiteKey());
        int coalesced = 0;
        for (IndexOperations.CustomGptIndexOperation operation : source.getOperations()) {
            if (pendingOperationKeys.add(pendingOperationKey(operation))) {
                kept.addOperation(operation);
            } else {
                coalesced++;
            }
        }
        if (coalesced > 0) {
            // INFO, not DEBUG: this drops indexing work. If the coalescing key is ever wrong, the symptom is
            // content silently not being indexed, and an operator needs evidence of the decision at the default
            // log level rather than having to reproduce it with DEBUG enabled.
            LOGGER.info("Coalesced {} index operation(s) for node(s) already queued in this publication;"
                    + " {} operation(s) dispatched", coalesced, kept.getOperations().size());
        }
        return kept;
    }

    private void releasePending(IndexOperations dispatched) {
        dispatched.getOperations().forEach(operation -> pendingOperationKeys.remove(pendingOperationKey(operation)));
    }

    /** Type is part of the key: a removal and an indexation of the same node are different work. */
    private static String pendingOperationKey(IndexOperations.CustomGptIndexOperation operation) {
        return operation.getType() + "|" + operation.getNodePath();
    }
    
    public void produceSiteAsynchronousIndexations(String sitePath, IndexOperations... operations) {
        CompletableFuture<Void>[] completableFuture = new CompletableFuture[operations.length];
        int i = 0;
        restartExecutorNThreads();
        for (IndexOperations operation : operations) {
            completableFuture[i++] = CompletableFuture.supplyAsync(getPerformIndexationSupplier(operation), executorNThreads);
        }
        try {
            updateIndexationTime(sitePath, PROP_INDEXATION_START, new GregorianCalendar());
            clearIndexationFailure(sitePath);
        } catch (RepositoryException e) {
            LOGGER.error("Failed to record indexation start time for site {}", sitePath, e);
        }
        CompletableFuture.allOf(completableFuture)
                .whenCompleteAsync((unused, throwable) -> recordIndexationOutcome(sitePath, throwable));
    }
    
    private Supplier<Void> getPerformIndexationSupplier(IndexOperations operations) {
        return () -> {
            try {
                performIndexation(operations);
            } catch (RepositoryException | IOException e) {
                LOGGER.error("Indexation failed due to: {}", e.getMessage(), e);
                // Propagate. Swallowing here left every future completing normally, so the caller recorded a
                // successful indexation for a run in which nothing was indexed.
                throw new CompletionException(e);
            }
            return null;
        };
    }
    
    private void performIndexation(IndexOperations operations)
            throws RepositoryException, IOException {
        if (operations == null || operations.isEmpty()) {
            // No operations to queueRequests
            LOGGER.error("Operations is empty, exiting performIndexation");
            return;
        }
        
        indexAllOperations(operations);
    }
    
    private void indexAllOperations(IndexOperations operations)
            throws RepositoryException, IOException {
        Indexer customGptIndexer = null;
        try {
            for (IndexOperations.CustomGptIndexOperation indexOperation : operations.getOperations()) {
                // Check that the path for the operation require indexation to avoid unwanted indexation
                customGptIndexer = indexOperation(operations, customGptIndexer, indexOperation);
            }

            if (customGptIndexer != null) {
                customGptIndexer.queueRequests(customGptClient, jahiaClient);
                failIfAnyNodeFailed(customGptIndexer);
            }
        } catch (NotConfiguredException e) {
            LOGGER.error(INDEXATION_FAILED_DUE_TO_CONFIGURATION_ISSUES, e.getMessage(), e);
        }

    }

    private Indexer indexOperation(IndexOperations operations, Indexer customGptIndexer,
            IndexOperations.CustomGptIndexOperation customGptIndexOperation)
            throws RepositoryException, NotConfiguredException {
        try {
            customGptIndexer = dispatchOperation(operations, customGptIndexer, customGptIndexOperation);
        } catch (PathNotFoundException e) {
            // Skip the operation as its node no longer exists
            LOGGER.info("Did not find indexation path: {}", e.getMessage());
        }
        return customGptIndexer;
    }

    private Indexer dispatchOperation(IndexOperations operations, Indexer customGptIndexer,
            IndexOperations.CustomGptIndexOperation customGptIndexOperation)
            throws RepositoryException, NotConfiguredException {
        final CustomGptOperationType opType = customGptIndexOperation.getType();
        switch (opType) {
            case NODE_INDEX:
                return handleNodeIndex(customGptIndexer, customGptIndexOperation);
            case NODE_REMOVE:
                return handleNodeRemove(customGptIndexer, customGptIndexOperation);
            case NODE_MOVE:
                return handleNodeMove(customGptIndexer, customGptIndexOperation);
            case SITE_INDEX:
                return handleSiteIndex(operations, customGptIndexer, customGptIndexOperation);
            case TREE_INDEX:
                return handleTreeIndex(customGptIndexer, customGptIndexOperation);
            default:
                return customGptIndexer;
        }
    }

    private Indexer handleNodeIndex(Indexer customGptIndexer, IndexOperations.CustomGptIndexOperation op) throws NotConfiguredException {
        customGptIndexer = initIndexer(customGptIndexer);
        if (acceptableToIndex(op.getNodePath(), customGptIndexer)) {
            indexNode(customGptIndexer, op);
        }
        return customGptIndexer;
    }

    private Indexer handleNodeRemove(Indexer customGptIndexer, IndexOperations.CustomGptIndexOperation op) {
        customGptIndexer = initIndexer(customGptIndexer);
        // we don't care of node remove under non indexed sites, since it's used by the RemoveSiteJob.
        customGptIndexer.addNodeToDelete(op.getCustomGptPageId(), op.getNodePath());
        return customGptIndexer;
    }

    private Indexer handleNodeMove(Indexer customGptIndexer, IndexOperations.CustomGptIndexOperation op) {
        customGptIndexer = initIndexer(customGptIndexer);
        if (acceptableToIndex(op.getNodePath(), customGptIndexer)) {
            customGptIndexer.addNodePathToMove(op.getSourcePath(), op.getNodePath());
        }
        return customGptIndexer;
    }

    private Indexer handleSiteIndex(IndexOperations operations, Indexer customGptIndexer,
            IndexOperations.CustomGptIndexOperation op) throws RepositoryException, NotConfiguredException {
        preIndexOperationHandler(operations);
        customGptIndexer = initIndexer(customGptIndexer);
        if (acceptableToIndex(op.getNodePath(), customGptIndexer)) {
            customGptIndexer.addSiteToIndex(customGptClient, jahiaClient, op.getNodePath());
        }
        postIndexOperationHandler(operations);
        return customGptIndexer;
    }

    private Indexer handleTreeIndex(Indexer customGptIndexer, IndexOperations.CustomGptIndexOperation op)
            throws RepositoryException, NotConfiguredException {
        LOGGER.info("Received a sub nodes index operation for following node {} in workspace live", op.getNodePath());
        customGptIndexer = initIndexer(customGptIndexer);
        final String path = op.getNodePath();
        if (acceptableToIndex(path, customGptIndexer)) {
            final JCRNodeWrapper node = customGptIndexer.getSystemSession().getNode(path);
            indexNode(customGptIndexer, op);
            customGptIndexer.addNodesToIndex(customGptClient, jahiaClient, node);
        }
        return customGptIndexer;
    }
    
    private void postIndexOperationHandler(IndexOperations operations) {
        final Map<String, Site> indexedSites = getIndexedSites();
        for (IndexOperations.CustomGptIndexOperation indexOperation : operations.getOperations()) {
            if (indexOperation.getType().equals(CustomGptOperationType.SITE_INDEX)) {
                indexedSites.computeIfPresent(indexOperation.getSiteKey(), (siteId, site) -> {
                    final Calendar date = new GregorianCalendar();
                    site.setIndexationEnd(date, () -> {
                        try {
                            updateIndexationTime(CustomGptConstants.PATH_SITES + siteId, PROP_INDEXATION_END, date);
                            FrameworkService.sendEvent(UNREGISTER_EVENT, constructTaskDetailsEvent(siteId, CUSTOM_GPT_SITE_INDEXATION), true);
                            LOGGER.info(REMOVED_FROM_REGISTRY, CUSTOM_GPT_SITE_INDEXATION, siteId);
                        } catch (RepositoryException e) {
                            LOGGER.error("Failed to record indexation end time: {}", e.getMessage());
                        }
                        return null;
                    });
                    
                    return site;
                });
            }
        }
    }
    
    private void preIndexOperationHandler(IndexOperations operations) {
        final Map<String, Site> indexedSites = getIndexedSites();
        for (IndexOperations.CustomGptIndexOperation indexOperation : operations.getOperations()) {
            if (indexOperation.getType().equals(CustomGptOperationType.SITE_INDEX)) {
                indexedSites.computeIfPresent(indexOperation.getSiteKey(), (siteId, site) -> {
                    final Calendar date = new GregorianCalendar();
                    site.setIndexationEnd(date, () -> {
                        try {
                            FrameworkService.sendEvent(UNREGISTER_EVENT, constructTaskDetailsEvent(siteId, CUSTOM_GPT_SITE_INDEXATION), true);
                            FrameworkService.sendEvent(REGISTER_EVENT, constructTaskDetailsEvent(siteId, CUSTOM_GPT_SITE_INDEXATION), true);
                            LOGGER.info(ADDED_TO_THE_REGISTRY, CUSTOM_GPT_SITE_INDEXATION, siteId);
                            updateIndexationTime(CustomGptConstants.PATH_SITES + siteId, PROP_INDEXATION_START, date);
                        } catch (RepositoryException e) {
                            LOGGER.error("Failed to record indexation start time: {}", e.getMessage());
                        }
                        return null;
                    });
                    
                    return site;
                });
            }
        }
    }
    
    private void indexNode(Indexer customGptIndexer, IndexOperations.CustomGptIndexOperation indexOperation) throws NotConfiguredException {
        customGptIndexer.addNodePathToIndex(indexOperation.getNodePath());
    }
    
    private Indexer initIndexer(Indexer customGptIndexer) {
        if (customGptIndexer == null) {
            return createIndexer();
        }
        return customGptIndexer;
    }
    
    public int getPendingIndexationOperations() {
        return getPendingCount(executor) + getPendingCount(executorFullIndexation) + getPendingCount(executorNThreads);
    }
    
    private int getPendingCount(ExecutorService exec) {
        if (exec instanceof ThreadPoolExecutor) {
            ThreadPoolExecutor tpe = (ThreadPoolExecutor) exec;
            return tpe.getQueue().size() + tpe.getActiveCount();
        }
        return 0;
    }
    
    private void handleJCREventListener(IndexerJCRListener listener, boolean register) {
        if (listener != null) {
            jahiaTemplateManagerService.getTemplatePackageRegistry().handleJCREventListener(listener, register);
        }
    }
    
    /**
     * Registers the live-workspace JCR listener, replacing any previously registered one.
     *
     * <p>Refuses to register a listener with an empty node-type filter. Jahia snapshots
     * {@code DefaultEventListener.getNodeTypes()} into the {@code EventConsumer} at registration, and
     * {@code JCRObservationManager.checkNodeTypeNames} rejects every event when that array is empty but non-null.
     * Such a listener is registered, reported healthy, and receives nothing for the life of the component - the
     * module looks started while indexing silently never happens. The listener resolves its filter once in its
     * constructor, so the array checked here is the same one Jahia registers.
     */
    private synchronized void registerJcrListeners() {
        final IndexerJCRListener candidate = new IndexerJCRListener(true, this, customGptConfig);
        if (!candidate.hasNodeTypeFilter()) {
            LOGGER.error("Not registering the CustomGPT JCR listener: its node-type filter is empty, which in Jahia"
                    + " matches no event at all - nothing would ever be indexed. This normally means the module"
                    + " configuration has not been delivered yet; registration is retried on the next"
                    + " configuration update. Any existing listener is left in place.");
            return;
        }

        unregisterJcrListeners();

        LOGGER.info("Registering JCR listeners");

        jcrListenerLive = candidate;

        if (journalEventReaderEnabled) {
            journalEventReader.replayMissedEvents(jcrListenerLive, journalEventReaderKey);
            journalEventReader.rememberLastProcessedJournalRevision(journalEventReaderKey);
        }

        handleJCREventListener(jcrListenerLive, true);
    }
    
    /**
     * Detaches the live-workspace JCR listener. This is the step that must never be skipped: the listener lives in
     * a JVM-wide static list in {@code JCRObservationManager}, matched by instance identity, so one left behind
     * survives the bundle and can only be removed by restarting the node.
     *
     * <p>The success message is logged only after the detach returns, so a failure cannot leave a log claiming it
     * worked. Note the residual limit: {@code TemplatePackageRegistry.handleJCREventListener} swallows
     * {@code RepositoryException} internally, so a failure to open the system session it needs - most likely
     * during shutdown, exactly when this runs - is logged by Jahia and reported to us as success.
     */
    /**
     * Registers the listener if it is missing, or replaces it if the configured node types have changed.
     *
     * <p>{@link #init()} is gated on {@code initialized}, so on its own it can neither recover a registration that
     * was refused for want of configuration nor pick up a later change to the indexed node types - the filter is
     * snapshotted at registration and frozen thereafter.
     */
    synchronized void refreshJcrListeners() {
        if (disposed || !settingsBean.isProcessingServer()) {
            return;
        }
        if (jcrListenerLive != null
                && sameNodeTypes(jcrListenerLive.getNodeTypes(), new IndexerJCRListener(true, this, customGptConfig).getNodeTypes())) {
            return;
        }
        registerJcrListeners();
    }

    private static boolean sameNodeTypes(String[] a, String[] b) {
        final String[] left = a.clone();
        final String[] right = b.clone();
        Arrays.sort(left);
        Arrays.sort(right);
        return Arrays.equals(left, right);
    }

    private synchronized void unregisterJcrListeners() {
        if (jcrListenerLive != null) {
            try {
                handleJCREventListener(jcrListenerLive, false);
            } catch (RuntimeException | LinkageError e) {
                LOGGER.error("The CustomGPT JCR listener could NOT be detached from Jahia's JVM-wide observation"
                        + " registry. It will keep receiving publication events from a classloader that is being"
                        + " invalidated, and can abort unrelated publications with NoClassDefFoundError. This node"
                        + " must be restarted before customgpt-ai is refreshed or updated again.", e);
                throw e;
            }
            LOGGER.info("Unregistered JCR listener for live workspace");
            jcrListenerLive = null;
        }
    }
    
    public void setJahiaTemplateManagerService(JahiaTemplateManagerService jahiaTemplateManagerService) {
        this.jahiaTemplateManagerService = jahiaTemplateManagerService;
    }
    
    private synchronized void init() {
        if (disposed) {
            // The component has been deactivated. stop() clears `initialized`, so without this latch an OSGi event
            // still in flight would fall straight through to registerJcrListeners() and strand a new listener.
            LOGGER.debug("Ignoring initialisation request: the CustomGPT service has already been stopped");
            return;
        }
        if (!initialized) {
            LOGGER.info("Starting service...");
            if (settingsBean.isProcessingServer()) {
                final CookieJar cookieJar = new CookieJar() {
                    @Override
                    public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
                        // cookies are not persisted; session auth is handled by the Bearer authenticator
                    }
                    
                    @Override
                    public List<Cookie> loadForRequest(HttpUrl arg0) {
                        if (customGptConfig.getJahiaServerCookieName() != null && !customGptConfig.getJahiaServerCookieName().isEmpty()
                                && customGptConfig.getJahiaServerCookieValue() != null && !customGptConfig.getJahiaServerCookieValue().isEmpty()) {
                            final Cookie cookie = new Cookie.Builder()
                                    .httpOnly()
                                    .secure()
                                    .name(customGptConfig.getJahiaServerCookieName())
                                    .value(customGptConfig.getJahiaServerCookieValue())
                                    .domain(customGptConfig.getJahiaServerCookieDomain())
                                    .build();
                            return Arrays.asList(cookie);
                        } else {
                            return Collections.emptyList();
                        }
                    }
                };
                jahiaClient = new OkHttpClient.Builder()
                        .cookieJar(cookieJar)
                        // Do not follow redirects: the session cookie must not be forwarded to redirect destinations.
                        .followRedirects(false)
                        .followSslRedirects(false)
                        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .build();
                customGptClient = new OkHttpClient.Builder()
                        // Do not follow redirects: every request carries the Bearer token, and a redirect to another
                        // host could forward the Authorization header to an attacker-controlled endpoint.
                        .followRedirects(false)
                        .followSslRedirects(false)
                        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .authenticator((route, response) -> {
                            if (response.request().header(HEADER_AUTHORIZATION) != null) {
                                return null;
                            }
                            return response.request().newBuilder()
                                    .addHeader(HEADER_AUTHORIZATION, BEARER_PREFIX + customGptConfig.getCustomGptToken())
                                    .build();
                        })
                        .addInterceptor(new RateLimitInterceptor(customGptConfig.getRateLimitRequestsPerSecond()))
                        .build();

                // Register the JCR listener LAST, once everything that can throw has succeeded.
                //
                // Registering it first would be a stranding hazard: the listener goes into a JVM-wide static list
                // in JCRObservationManager, but if anything after that throws - RateLimitInterceptor rejects a
                // rateLimit.requestsPerSecond of 0, which a stale .cfg can easily supply - the exception escapes
                // activate(), and per OSGi Compendium 112.5.8 a component whose activate() throws is never
                // deactivated. stop() would then never run, and every safeguard in it is moot: the listener stays
                // registered against a dead classloader for the life of the JVM. That is JAHIACOM-1675 through a
                // door none of the shutdown hardening can close.
                registerJcrListeners();
            }
            initialized = true;
            LOGGER.info("...service started");
        }
    }
    
    public void start() {
        registerEventHandler();
        init();
    }
    
    /**
     * Releases every resource held by this component. Invoked from {@link #deactivate()}.
     *
     * <p>Ordering and failure handling are deliberate, and both matter for correctness:
     *
     * <p><b>1. Detaching the JCR listener is the step that must never be skipped.</b>
     * {@code JCRObservationManager} keeps its listeners in a JVM-wide {@code static} list, and both its
     * {@code removeEventListener} (reference identity) and {@code TemplatePackageRegistry}'s de-duplication
     * ({@code getListenerClass()}, i.e. {@code Class} identity) can only match the exact instance registered by
     * <em>this</em> bundle generation. If it is skipped, the listener survives the bundle: it keeps receiving
     * publication events from a classloader that is no longer valid, and neither a redeploy nor a wiring refresh
     * can evict it - only a JVM restart can. Everything else here merely leaks within this JVM run. It is
     * therefore called unconditionally, and only the cheap, idempotent steps below may precede it.
     *
     * <p><b>2. The door is closed before the teardown starts.</b> {@link #disposed} is latched first and the
     * {@link EventHandler} registration is withdrawn next, so no OSGi event can re-enter {@link #init()} and
     * register a fresh listener from a bundle that is on its way out.
     *
     * <p><b>3. Every step is isolated.</b> A failure in one cleanup must never skip the others; in particular an
     * already-unregistered OSGi service used to throw {@link IllegalStateException} out of {@code stop()} and
     * abort the listener de-registration.
     */
    public void stop() {
        // Latch first: it closes the door on init() before anything below can yield to another thread.
        disposed = true;
        // Then stop new events reaching handleEvent(), so nothing can re-enter init() while we are tearing down.
        runQuietly("unregister event handler", this::unregisterEventHandler);
        // Then detach the listener - unconditionally. jcrListenerLive being non-null IS the authoritative signal
        // that a listener was registered, and unregisterJcrListeners() already checks it; re-deriving that from
        // settingsBean could only ever add a condition under which this step silently does not run.
        runQuietly("unregister JCR listeners", this::unregisterJcrListeners);
        if (journalEventReaderEnabled) {
            runQuietly("remember last processed journal revision",
                    () -> journalEventReader.rememberLastProcessedJournalRevision(journalEventReaderKey));
        }
        runQuietly("shut down full indexation executor", () -> shutdownAndAwaitTermination(executorFullIndexation));
        runQuietly("shut down indexation executor", () -> shutdownAndAwaitTermination(executor));
        runQuietly("shut down multi-threaded indexation executor", () -> shutdownAndAwaitTermination(executorNThreads));
        // Last: the pools have had their full graceful window, so anything still in flight is already lost.
        runQuietly("close the HTTP clients", this::closeHttpClients);
        initialized = false;
    }

    /**
     * Cancels every in-flight HTTP call and releases both clients.
     *
     * <p>Called from {@link #stop()} only, once, after every pool has been shut down. It must NOT be called from
     * {@link #shutdownAndAwaitTermination}: that helper also runs during normal operation, on a caller-local pool
     * (see {@code purgeAllPages}), and these clients are component-wide - tearing them down there would leave a
     * live component with dead clients and no way back, since {@code init()} is a no-op once initialised. Running
     * it per-pool would also cancel calls owned by pools whose own graceful window had not yet opened.
     *
     * <p>Cancelling is destructive by nature: indexing a node is a {@code DELETE} of the previous CustomGPT page
     * followed by a re-{@code add} (see {@code CustomGptIndexerNodeHandler}), so a call cancelled mid-sequence
     * removes a page from the customer's project without recreating it. That is why the pools get a graceful
     * window at least as long as one call timeout before this runs.
     */
    private synchronized void closeHttpClients() {
        if (httpClientsClosed) {
            return;
        }
        httpClientsClosed = true;
        closeHttpClient(customGptClient);
        closeHttpClient(jahiaClient);
    }

    /**
     * Runs one shutdown step, logging and swallowing any {@link RuntimeException} so that a single failing step
     * cannot prevent the remaining ones from running. Shutdown is best-effort by nature: there is no caller left
     * that could act on the failure, and propagating it would only strand the resources cleaned up afterwards.
     */
    private void runQuietly(String what, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | LinkageError e) {
            // LinkageError matters as much as RuntimeException here: stop() runs while the bundle is going down,
            // which is precisely when classloading turns fragile. Letting one escape would abort the remaining
            // steps - including the listener de-registration - and strand the listener for the life of the JVM.
            LOGGER.error("Failed to {} while stopping the CustomGPT service", what, e);
        }
    }
    
    private void closeHttpClient(OkHttpClient httpClient) {
        if (httpClient != null) {
            httpClient.dispatcher().cancelAll();
            httpClient.connectionPool().evictAll();
            httpClient.dispatcher().executorService().shutdown(); // shutdown dispatcher's executor
            try {
                httpClient.dispatcher().executorService().awaitTermination(5, TimeUnit.SECONDS); // wait for termination
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                LOGGER.error("Impossible to stop http client", ex);
            }
        }
    }
    
    private void shutdownAndAwaitTermination(ExecutorService pool) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(POOL_GRACEFUL_SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
                final int running = pool instanceof ThreadPoolExecutor ? ((ThreadPoolExecutor) pool).getActiveCount() : 0;
                // shutdownNow() hands back the tasks it never started. Dropping that list on the floor is how a
                // shutdown silently loses queued indexation work, so name the cost - unconditionally, because
                // cancelling a running task is a loss even when the queue behind it is empty.
                final int discarded = pool.shutdownNow().size();
                LOGGER.warn("Pool {} did not drain within {}s; cancelling {} running and discarding {} queued"
                        + " indexation operation(s). Run a full re-indexation of the affected sites: some pages"
                        + " may have been removed from the CustomGPT project without being re-added.",
                        pool, POOL_GRACEFUL_SHUTDOWN_SECONDS, running, discarded);
            }
            // Wait a while for tasks to respond to being cancelled
            if (!pool.awaitTermination(POOL_FORCED_SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
                LOGGER.error("Pool {} still has tasks running after shutdown; they will keep executing module code"
                        + " on a classloader that is being invalidated", pool);
            }
        } catch (InterruptedException e) {
            LOGGER.warn("CustomGpt service was interrupted while shutting down tasks");
            // (Re-)Cancel if current thread also interrupted
            pool.shutdownNow();
            // Preserve interrupt status
            Thread.currentThread().interrupt();
        }
    }
    
    public void setJournalEventReaderKey(String journalEventReaderKey) {
        this.journalEventReaderKey = journalEventReaderKey;
    }
    
    public void setJournalEventReaderEnabled(boolean journalEventReaderEnabled) {
        this.journalEventReaderEnabled = journalEventReaderEnabled;
    }

    /**
     * Receives OSGi events from {@link CustomGptConstants#EVENT_TOPIC}.
     * On {@link CustomGptConstants#EVENT_TYPE_CONFIG_UPDATED_REQUIRE_REINDEX} it re-initialises the HTTP clients,
     * schedules re-indexation for all sites, then resets {@code scheduleJobASAP} to {@code false} to prevent
     * re-triggering on the next config reload.
     */
    @Override
    public void handleEvent(Event event) {
        final String type = (String) event.getProperty("type");
        LOGGER.info("Received event from topic {} of type {}", event.getTopic(), type);

        if (disposed) {
            // stop() withdraws the EventHandler registration, but EventAdmin delivery is not transactional: a
            // thread can already be inside this method. Everything below has side effects that outlive us -
            // init() registers a JCR listener, reIndexUsingJob() schedules a Quartz job whose class belongs to
            // the bundle being torn down - so refuse the whole event, not just init().
            LOGGER.warn("Ignoring {} event: the CustomGPT service has already been stopped", type);
            return;
        }

        if ((CustomGptConstants.EVENT_TYPE_TRANSPORT_CLIENT_SERVICE_AVAILABLE.equals(type)
                || CustomGptConstants.EVENT_TYPE_CONFIG_UPDATED.equals(type)
                || CustomGptConstants.EVENT_TYPE_CONFIG_UPDATED_REQUIRE_REINDEX.equals(type))
                && customGptConfig.isConfigured()) {
            init();
            // init() is a no-op once initialised, so it can neither recover a refused registration nor pick up a
            // change to the indexed node types. That is what this call is for.
            refreshJcrListeners();
            if (CustomGptConstants.EVENT_TYPE_CONFIG_UPDATED_REQUIRE_REINDEX.equals(type)) {
                reIndexUsingJob();
                resetScheduleJobASAP();
            }
        }
    }

    /**
     * Writes {@code scheduleJobASAP=false} back to OSGi ConfigurationAdmin.
     * This re-fires {@link Config#updated} but with {@code false}, so it emits only
     * {@link CustomGptConstants#EVENT_TYPE_CONFIG_UPDATED}, safely breaking the re-index cycle.
     */
    private void resetScheduleJobASAP() {
        try {
            final ConfigurationAdmin configAdmin = org.jahia.osgi.BundleUtils.getOsgiService(ConfigurationAdmin.class, null);
            if (configAdmin == null) {
                return;
            }
            final org.osgi.service.cm.Configuration config = configAdmin.getConfiguration("org.jahia.community.modules.customgpt", null);
            Dictionary<String, Object> props = config.getProperties();
            if (props == null) {
                props = new Hashtable<>();
            }
            props.put("org.jahia.community.modules.customgpt.scheduleJobASAP", Boolean.FALSE);
            config.update(props);
            LOGGER.info("Reset scheduleJobASAP to false after scheduling indexation jobs");
        } catch (IOException e) {
            LOGGER.warn("Failed to reset scheduleJobASAP property: {}", e.getMessage());
        }
    }
    
    private void registerEventHandler() {
        final Map<String, Object> props = new HashMap<>();
        props.put(org.osgi.framework.Constants.SERVICE_PID, getClass().getName() + ".EventHandler");
        props.put(org.osgi.framework.Constants.SERVICE_DESCRIPTION,
                "CustomGpt service event handler");
        props.put(org.osgi.framework.Constants.SERVICE_VENDOR, "Jahia Solutions Group SA");
        props.put(EventConstants.EVENT_TOPIC, CustomGptConstants.EVENT_TOPIC);
        
        eventHandlerServiceRegistration = bundleContext.registerService(EventHandler.class, this, new MapToDictionary(props));
    }
    
    /**
     * Unregisters the OSGi event handler. Idempotent: the registration is cleared before the call, and an
     * {@link IllegalStateException} from a service the framework already unregistered while the bundle was
     * stopping is expected rather than exceptional.
     */
    private void unregisterEventHandler() {
        final ServiceRegistration<EventHandler> registration = eventHandlerServiceRegistration;
        eventHandlerServiceRegistration = null;
        if (registration != null) {
            LOGGER.info("Unregistering Event Handler");
            try {
                registration.unregister();
            } catch (IllegalStateException e) {
                LOGGER.debug("Event handler service was already unregistered by the framework", e);
            }
        }
    }
    
    public Map<String, Site> getIndexedSites() {
        try {
            return JCRTemplate.getInstance().doExecuteWithSystemSession(this::getIndexedSites);
        } catch (RepositoryException e) {
            LOGGER.warn("Issue while fetching list of sites for CustomGpt", e);
            return Collections.emptyMap();
        }
    }
    
    public Map<String, Site> getIndexedSites(JCRSessionWrapper session) {
        try {
            final Map<String, Site> sites = new LinkedHashMap<>();
            final QueryManagerWrapper queryManager = session.getWorkspace().getQueryManager();
            final QueryWrapper query = queryManager.createQuery(
                    "SELECT * FROM [" + CustomGptConstants.MIX_INDEXABLE_SITE + "] AS site WHERE ISCHILDNODE(site, '" + CustomGptConstants.PATH_SITES + "') ORDER BY localname()",
                    Query.JCR_SQL2);
            for (JCRNodeWrapper jcrNodeWrapper : query.execute().getNodes()) {
                final Site site = buildSite(jcrNodeWrapper);
                sites.put(site.getSiteKey(), site);
            }
            logSites(sites);
            return sites;
        } catch (RepositoryException e) {
            LOGGER.warn("Issue while fetching list of sites for CustomGpt", e);
            return Collections.emptyMap();
        }
    }

    private static Site buildSite(JCRNodeWrapper jcrNodeWrapper) throws RepositoryException {
        final Site site = new Site(jcrNodeWrapper.getName(), jcrNodeWrapper.getPath());
        if (jcrNodeWrapper.hasProperty(PROP_INDEXATION_START)) {
            site.setIndexationStart(jcrNodeWrapper.getProperty(PROP_INDEXATION_START).getDate());
        }
        if (jcrNodeWrapper.hasProperty(PROP_INDEXATION_END)) {
            site.setIndexationEnd(jcrNodeWrapper.getProperty(PROP_INDEXATION_END).getDate());
        }
        if (jcrNodeWrapper.hasProperty(PROP_INDEXATION_SCHEDULED)) {
            site.setIndexationScheduled(jcrNodeWrapper.getProperty(PROP_INDEXATION_SCHEDULED).getDate());
        }
        if (jcrNodeWrapper.hasProperty(PROP_INDEXATION_FAILED)) {
            site.setIndexationFailed(jcrNodeWrapper.getProperty(PROP_INDEXATION_FAILED).getDate());
        }
        return site;
    }

    private static void logSites(Map<String, Site> sites) {
        if (LOGGER.isDebugEnabled()) {
            sites.forEach((s, site) -> LOGGER.debug("Site {}, has props: start {}, end {}, scheduled {}", s,
                    site.getIndexationStart() != null ? site.getIndexationStart().toInstant() : UNSET,
                    site.getIndexationEnd() != null ? site.getIndexationEnd().toInstant() : UNSET,
                    site.getIndexationScheduled() != null ? site.getIndexationScheduled().toInstant() : UNSET));
        }
    }
    
    /**
     * Returns {@code true} when {@code path} is a candidate for indexing: it must be under {@code /sites/} or
     * {@code /trash-}, and must not end with the internal {@code customGptPageId} or {@code jcr:lastModified}
     * property names (which would indicate a property-path rather than a node-path).
     */
    public boolean acceptablePathToIndex(String path) {
        return ((path.startsWith("/trash-") || SITE_MATCHER.matcher(path).matches()) && !path.endsWith(CustomGptConstants.PROP_CUSTOM_GPT_PAGE_ID) && !path.endsWith(Constants.JCR_LASTMODIFIED));
    }

    /**
     * Returns {@code true} when {@code path} is both shaped like an indexable path and owned by a site an
     * administrator has actually registered for CustomGPT indexation.
     *
     * <p>This is the gate {@link #acceptablePathToIndex(String)} was mistaken for. That method only ever checked
     * the <em>shape</em> of a path, so any publication anywhere under {@code /sites/} produced an index operation,
     * whether or not the site had been registered with {@code addSite}. The effect was measurable in production:
     * a {@code store.jahia.com} page reached a corpus intended to hold one site's content, and there is no way to
     * filter a CustomGPT corpus by origin afterwards - it had to be identified and deleted by hand.
     *
     * <p>Deletes deliberately do NOT pass through here; see {@code handleNodeRemove}. Removing a page that should
     * not be in the corpus is always safe, and a delete is already self-limiting because it is driven by a mapping
     * node that exists only for content this module indexed in the first place.
     */
    boolean acceptableToIndex(String path, Indexer customGptIndexer) {
        if (!acceptablePathToIndex(path)) {
            return false;
        }
        try {
            return isInRegisteredSite(path, customGptIndexer.getSystemSession());
        } catch (RepositoryException e) {
            LOGGER.warn("Not indexing {}: cannot open a session to check whether its site is registered", path, e);
            return false;
        }
    }

    /**
     * Whether the site owning {@code path} carries {@code jmix:customGptIndexableSite}.
     *
     * <p>Fails CLOSED: an unresolvable site, a missing site node or a repository error all return {@code false}.
     * The asymmetry is deliberate. Failing to index a page that should have been indexed is repaired by the next
     * publication or a re-index; indexing a page that should not have been contaminates a corpus that cannot be
     * filtered by origin.
     */
    boolean isInRegisteredSite(String path, JCRSessionWrapper session) {
        final String siteKey = siteKeyOf(path);
        if (siteKey == null) {
            return false;
        }
        final String sitePath = CustomGptConstants.PATH_SITES + siteKey;
        try {
            if (!session.nodeExists(sitePath)) {
                LOGGER.warn("Not indexing {}: its site node {} does not exist", path, sitePath);
                return false;
            }
            if (!session.getNode(sitePath).isNodeType(CustomGptConstants.MIX_INDEXABLE_SITE)) {
                LOGGER.debug("Not indexing {}: site {} is not registered for CustomGPT indexation", path, siteKey);
                return false;
            }
            return true;
        } catch (RepositoryException e) {
            LOGGER.warn("Not indexing {}: cannot determine whether site {} is registered for CustomGPT indexation",
                    path, siteKey, e);
            return false;
        }
    }

    /**
     * The site key in {@code /sites/<key>[/...]}, or {@code null} when the path names no site.
     *
     * <p>A {@code /trash-} path yields {@code null} on purpose: it only ever accompanies a delete, and a delete
     * is not gated on registration.
     */
    static String siteKeyOf(String path) {
        if (path == null || !path.startsWith(CustomGptConstants.PATH_SITES)) {
            return null;
        }
        final String rest = path.substring(CustomGptConstants.PATH_SITES.length());
        final int slash = rest.indexOf('/');
        final String siteKey = slash < 0 ? rest : rest.substring(0, slash);
        return siteKey.isEmpty() ? null : siteKey;
    }
    
    /**
     * Records how a site indexation ended.
     *
     * <p>The end timestamp is written either way, so the site never appears stuck mid-run - {@code Site} derives
     * "in progress" from the absence of an end timestamp. What distinguishes the two outcomes is the failure
     * marker, which the admin status reads: without it a run in which every operation failed was reported as
     * COMPLETED, because nothing that the status is derived from recorded the failure.
     */
    /**
     * Surfaces per-node failures that {@code CustomGptIndexerNodeHandler} caught individually.
     *
     * <p>Without this the run completes normally however many nodes failed, and the site is recorded as
     * successfully indexed - the failures are visible only as individual log lines.
     */
    private static void failIfAnyNodeFailed(Indexer indexer) throws IOException {
        final List<String> failures = indexer.getFailures();
        if (!failures.isEmpty()) {
            throw new IOException(failures.size() + " node(s) could not be indexed: " + failures);
        }
    }

    void recordIndexationOutcome(String sitePath, Throwable throwable) {
        final Calendar now = new GregorianCalendar();
        try {
            updateIndexationTime(sitePath, PROP_INDEXATION_END, now);
            if (throwable != null) {
                updateIndexationTime(sitePath, PROP_INDEXATION_FAILED, now);
                LOGGER.error("Indexation of site {} ended with at least one failed operation; it is reported as"
                        + " FAILED, not COMPLETED. Re-run it once the cause is fixed.", sitePath, throwable);
            }
        } catch (RepositoryException e) {
            LOGGER.error("Failed to record the indexation outcome for site {}", sitePath, e);
        }
    }

    private void updateIndexationTime(String path, String property, Calendar date) throws RepositoryException {
        JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
            final JCRNodeWrapper node = session.getNode(path);
            node.setProperty(property, date);
            session.save();
            LOGGER.info("Site {} indexation has {} at {}", path, (property.equals(PROP_INDEXATION_START) ? "started" : "ended"), date.toInstant());
            return null;
        });
    }

    /** Clears the previous run's failure marker, so a site does not stay reported as FAILED after a good run. */
    private void clearIndexationFailure(String path) throws RepositoryException {
        JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
            final JCRNodeWrapper node = session.getNode(path);
            if (node.hasProperty(PROP_INDEXATION_FAILED)) {
                node.getProperty(PROP_INDEXATION_FAILED).remove();
                session.save();
            }
            return null;
        });
    }
    
    private Map<String, Object> constructTaskDetailsEvent(String taskTarget, String taskService) {
        final Map<String, Object> taskDetailsMap = new HashMap<>();
        taskDetailsMap.put("name", taskService + ": " + taskTarget);
        taskDetailsMap.put("service", taskService);
        taskDetailsMap.put("started", new GregorianCalendar());
        return taskDetailsMap;
    }
    
    public JCRSessionWrapper getSystemSession(JahiaUser user, String workspace, Locale locale) throws RepositoryException {
        // NOTE: setCurrentUser mutates the thread-local JCRSessionFactory current user as a side effect, which is a
        // shared-state concern when indexer tasks run on pooled executor threads. It is left in place deliberately:
        // the index pipeline relies on the current-user being the root/system user for permission resolution, and a
        // safe behaviour-preserving fix would require threading the user through the call chain. Do not "fix" by
        // removing the guard without also passing the user explicitly to every downstream JCR call.
        final JCRSessionWrapper systemSession = JCRTemplate.getInstance().getSessionFactory().getCurrentSystemSession(workspace, locale, null);
        if (JCRSessionFactory.getInstance().getCurrentUser() == null) {
            JCRSessionFactory.getInstance().setCurrentUser(user);
        }
        return systemSession;
    }
    
    public boolean skipIndexationForNode(JCRNodeWrapper node) throws RepositoryException {
        if (node == null) {
            return true;
        }
        return node.isNodeType(CustomGptConstants.MIX_SKIP_INDEX);
    }

    /**
     * Calls {@code GET /projects/{projectId}} and returns the {@code project_name} field.
     * Returns {@code null} if the project ID is empty, the client is null, or the API call fails.
     */
    /**
     * Resolves the configured CustomGPT API base URL, applying the default when unset and stripping a trailing
     * slash. Every request built from the result carries the Bearer token, so the URL is validated as https://
     * here (a {@code .cfg} edit bypasses the {@code saveSettings} gate) to keep the token off cleartext channels.
     *
     * @throws IllegalStateException when the resolved base URL is not a valid {@code https://} URL
     */
    private String resolveValidatedApiBaseUrl() {
        return SecurityUtils.resolveHttpsBaseUrl(
                customGptConfig.getCustomGptApiBaseUrl(), CustomGptConstants.DEFAULT_CUSTOM_GPT_API_BASE_URL);
    }

    public String getProjectName() {
        final String projectId = customGptConfig.getCustomGptProjectId();
        if (projectId == null || projectId.isEmpty()) {
            return null;
        }
        if (customGptClient == null) {
            LOGGER.warn("CustomGPT HTTP client is not initialised; cannot fetch project name");
            return null;
        }
        final String baseUrl;
        try {
            baseUrl = resolveValidatedApiBaseUrl();
        } catch (IllegalStateException e) {
            LOGGER.warn("Cannot fetch CustomGPT project name: {}", e.getMessage());
            return null;
        }
        final Request request = new Request.Builder()
                .url(String.format("%s/projects/%s", baseUrl, projectId))
                .get()
                .addHeader(HEADER_ACCEPT, MEDIA_TYPE_JSON)
                .addHeader(HEADER_AUTHORIZATION, BEARER_PREFIX + customGptConfig.getCustomGptToken())
                .build();
        try (Response response = customGptClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                LOGGER.warn("Failed to fetch CustomGPT project name for project {}: {}", projectId, response.code());
                return null;
            }
            if (response.body() == null) {
                LOGGER.warn("Empty response body fetching CustomGPT project name for project {}", projectId);
                return null;
            }
            final JSONObject body = new JSONObject(response.body().string());
            final JSONObject data = body.optJSONObject("data");
            return data != null ? data.optString("project_name", null) : null;
        } catch (IOException e) {
            LOGGER.warn("Error fetching CustomGPT project name: {}", e.getMessage());
            return null;
        }
    }

    /**
     * The public embed details for the Search Generative Experience widget, cached for
     * {@value #SGE_CACHE_TTL_MS} ms.
     *
     * <p>Resolves the project id from configuration, the public deployment key from
     * {@code GET /projects/{id}} (the {@code shareable_slug} field of the same payload the project name
     * comes from), and availability from {@code GET /limits/usage}, which reports the team's
     * {@code max_queries} per billing cycle against {@code current_queries}.
     *
     * <p><strong>Fails open.</strong> Availability is false only when the quota is positively known to be
     * used up. A call that times out, returns 429, or comes back without the fields reads as available.
     * Hiding a working feature because of a transient error is the worse failure of the two: the visitor
     * loses a route that would have worked and nothing says why, whereas letting a doomed query through
     * costs one CustomGPT error message that the visitor can see and act on. The same reasoning does not
     * apply to a definite "quota used up", which is why that case does close the feature.
     *
     * @return a deployment; {@link SgeDeployment#isUsable()} is the single question a caller should ask
     */
    public SgeDeployment getSgeDeployment() {
        final CachedSgeDeployment cached = sgeCache.get();
        if (cached != null && !cached.isExpired()) {
            return cached.deployment;
        }

        final SgeDeployment resolved = resolveSgeDeployment();
        // Cached whatever the outcome, including "not configured": a site that has not set this up must not
        // pay for a lookup on every page view either.
        sgeCache.set(new CachedSgeDeployment(resolved, System.currentTimeMillis() + SGE_CACHE_TTL_MS));
        return resolved;
    }

    /** Drops the cached deployment, so the next read re-resolves. Called when the configuration changes. */
    public void invalidateSgeDeployment() {
        sgeCache.set(null);
    }

    private SgeDeployment resolveSgeDeployment() {
        final String projectId = customGptConfig.getCustomGptProjectId();
        if (projectId == null || projectId.isEmpty() || customGptClient == null) {
            return SgeDeployment.notConfigured();
        }

        final String baseUrl;
        try {
            baseUrl = resolveValidatedApiBaseUrl();
        } catch (IllegalStateException e) {
            LOGGER.warn("Cannot resolve the CustomGPT SGE deployment: {}", e.getMessage());
            return SgeDeployment.notConfigured();
        }

        final String shareableKey = fetchShareableKey(baseUrl, projectId);
        if (shareableKey == null) {
            // Without the public key there is nothing to embed, so this is "not configured" rather than
            // "unavailable" -- the caller renders nothing either way, but the distinction is in the logs.
            return SgeDeployment.notConfigured();
        }

        return SgeDeployment.of(projectId, shareableKey, hasQueryQuotaLeft(baseUrl));
    }

    /** The {@code shareable_slug} of the project: CustomGPT's public deployment key. */
    private String fetchShareableKey(String baseUrl, String projectId) {
        final Request request = new Request.Builder()
                .url(String.format("%s/projects/%s", baseUrl, projectId))
                .get()
                .addHeader(HEADER_ACCEPT, MEDIA_TYPE_JSON)
                .addHeader(HEADER_AUTHORIZATION, BEARER_PREFIX + customGptConfig.getCustomGptToken())
                .build();
        try (Response response = customGptClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                LOGGER.warn("Cannot read the CustomGPT deployment key for project {}: HTTP {}", projectId,
                        response.code());
                return null;
            }
            final JSONObject data = new JSONObject(response.body().string()).optJSONObject("data");
            if (data == null) {
                return null;
            }
            final String slug = data.optString("shareable_slug", null);
            return slug == null || slug.isEmpty() ? null : slug;
        } catch (IOException e) {
            LOGGER.warn("Error reading the CustomGPT deployment key: {}", e.getMessage());
            return null;
        }
    }

    /** @return true unless the team's query quota for this billing cycle is positively known to be spent */
    private boolean hasQueryQuotaLeft(String baseUrl) {
        final Request request = new Request.Builder()
                .url(baseUrl + "/limits/usage")
                .get()
                .addHeader(HEADER_ACCEPT, MEDIA_TYPE_JSON)
                .addHeader(HEADER_AUTHORIZATION, BEARER_PREFIX + customGptConfig.getCustomGptToken())
                .build();
        try (Response response = customGptClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                LOGGER.warn("Could not read the CustomGPT query quota (HTTP {}); assuming it is available",
                        response.code());
                return true;
            }
            return hasQueryQuotaLeft(new JSONObject(response.body().string()).optJSONObject("data"));
        } catch (IOException e) {
            LOGGER.warn("Error reading the CustomGPT query quota ({}); assuming it is available", e.getMessage());
            return true;
        }
    }

    /**
     * The quota decision, separated from the request so it can be tested without a network.
     *
     * <p>Every uncertain case answers true. A missing payload, a missing field, or a non-positive
     * {@code max_queries} (which is how an unmetered plan reads) are all "we do not know", and the choice
     * on not knowing is to leave the feature on.
     */
    static boolean hasQueryQuotaLeft(JSONObject limits) {
        if (limits == null) {
            return true;
        }
        final int max = limits.optInt("max_queries", -1);
        final int current = limits.optInt("current_queries", -1);
        if (max <= 0 || current < 0) {
            return true;
        }
        return current < max;
    }

    /** A resolved deployment and the moment it stops being trusted. */
    private static final class CachedSgeDeployment {

        private final SgeDeployment deployment;
        private final long expiresAt;

        private CachedSgeDeployment(SgeDeployment deployment, long expiresAt) {
            this.deployment = deployment;
            this.expiresAt = expiresAt;
        }

        private boolean isExpired() {
            return System.currentTimeMillis() >= expiresAt;
        }
    }

    /**
     * Deletes every page registered in the CustomGPT project in a streaming-batch fashion:
     * repeatedly fetches the <em>first</em> result page, deletes those IDs concurrently,
     * then re-queries until the first page comes back empty.
     *
     * <p>Always re-querying from the first page (rather than following {@code next_page_url})
     * avoids offset-pagination drift: after deleting the items on page 1, the items that were
     * on page 2 shift into page 1, so following {@code next_page_url} would skip them.
     *
     * @return the number of pages successfully deleted
     */
    /**
     * Repairs pages this module indexed without a URL; see {@link PageUrlRepair}.
     *
     * @return the number of pages whose URL was repaired
     */
    public int repairMissingPageUrls(String siteKey, java.util.Collection<String> pageIds, boolean dryRun)
            throws IOException, RepositoryException {
        // Validate before touching any state, so a bad site key fails the same way whether or not the module
        // happens to be initialised. The key is interpolated into a JCR-SQL2 path constraint, so it has to be a
        // single safe segment: deriving it with siteKeyOf would silently TRUNCATE "a/b" to "a" and accept it.
        if (siteKey == null || !SITE_KEY_PATTERN.matcher(siteKey).matches()) {
            throw new IllegalArgumentException("Invalid site key; expected ^[\\w-]+$ but got: " + siteKey);
        }
        if (customGptClient == null) {
            throw new IOException("CustomGPT HTTP client is not initialised; cannot repair page URLs");
        }
        return new PageUrlRepair(customGptClient, customGptConfig.getCustomGptProjectId(),
                resolveValidatedApiBaseUrl()).repairSite(siteKey, pageIds, dryRun);
    }

    /**
     * Reports, and optionally deletes, pages in the project that no mapping node claims; see {@link OrphanedPages}.
     *
     * @param pageIds the orphan ids to delete; required when not a dry run, because an orphan is not
     *                necessarily redundant and may be the only copy of its content
     * @param siteKey used only to render the probable-node hint in the report; the sweep itself is
     *                project-wide, because scoping it to a site would classify other sites' pages as orphaned
     * @param dryRun when true, the orphans are logged and nothing is deleted
     * @return the number of orphaned pages found (deleted, when not a dry run)
     */
    public int sweepOrphanedPages(java.util.Collection<String> pageIds, String siteKey, boolean dryRun)
            throws IOException, RepositoryException {
        if (customGptClient == null) {
            throw new IOException("CustomGPT HTTP client is not initialised; cannot sweep orphaned pages");
        }
        return new OrphanedPages(customGptClient, customGptConfig.getCustomGptProjectId(),
                resolveValidatedApiBaseUrl()).sweep(pageIds, siteKey, dryRun);
    }

    public int purgeAllPages() throws IOException {
        final String projectId = customGptConfig.getCustomGptProjectId();
        // projectId is free-form admin config; strip CR/LF before logging to prevent log forging.
        final String safeProjectId = SecurityUtils.sanitizeForLog(projectId);
        LOGGER.info("[purgeAllPages] Starting purge for project {}", safeProjectId);

        if (customGptClient == null) {
            throw new IOException("CustomGPT HTTP client is not initialised; cannot purge pages");
        }
        final String baseUrl = resolveValidatedApiBaseUrl();

        int batchSize;
        try {
            batchSize = customGptConfig.getBulkOperationsBatchSize();
        } catch (NotConfiguredException e) {
            LOGGER.warn("[purgeAllPages] Batch size unavailable, falling back to {}: {}", DEFAULT_BATCH_SIZE, e.getMessage());
            batchSize = DEFAULT_BATCH_SIZE;
        }
        if (batchSize <= 0) {
            batchSize = DEFAULT_BATCH_SIZE;
        }

        final String firstPageUrl = String.format("%s/projects/%s/pages", baseUrl, projectId);
        int totalDeleted = 0;
        int round = 1;

        // Cap threads to the rate limit so we never create more concurrent callers than tokens-per-second.
        // A single executor is reused across all purge rounds to avoid repeated thread-pool creation/teardown.
        // Guard against a zero/negative rate limit, which would make newFixedThreadPool throw.
        final int threadCount = Math.max(1, Math.min(batchSize, customGptConfig.getRateLimitRequestsPerSecond()));
        final ExecutorService batchExecutor = Executors.newFixedThreadPool(threadCount);
        try {
            while (true) {
                LOGGER.info("[purgeAllPages] Round {}: fetching first result page from CustomGPT", round);
                final List<Long> pageIds = fetchOnePage(firstPageUrl);
                if (pageIds.isEmpty()) {
                    break;
                }
                LOGGER.info("[purgeAllPages] Round {}: {} page(s) to delete (batch size {})", round, pageIds.size(), batchSize);
                totalDeleted += deleteAllPages(pageIds, baseUrl, projectId, batchSize, batchExecutor);
                LOGGER.info("[purgeAllPages] Round {} complete — {} page(s) deleted so far", round, totalDeleted);
                round++;
            }
        } finally {
            shutdownAndAwaitTermination(batchExecutor);
        }

        LOGGER.info("[purgeAllPages] Purge complete — deleted {} page(s) from project {}", totalDeleted, safeProjectId);
        return totalDeleted;
    }

    private List<Long> fetchOnePage(String url) throws IOException {
        final Request listRequest = new Request.Builder()
                .url(url)
                .get()
                .addHeader(HEADER_ACCEPT, MEDIA_TYPE_JSON)
                .addHeader(HEADER_AUTHORIZATION, BEARER_PREFIX + customGptConfig.getCustomGptToken())
                .build();
        try (Response listResponse = customGptClient.newCall(listRequest).execute()) {
            if (!listResponse.isSuccessful()) {
                LOGGER.error("[purgeAllPages] Failed to list CustomGPT pages (HTTP {}), stopping", listResponse.code());
                return Collections.emptyList();
            }
            if (listResponse.body() == null) {
                LOGGER.error("[purgeAllPages] Empty response body when listing CustomGPT pages, stopping");
                return Collections.emptyList();
            }
            final JSONObject body = new JSONObject(listResponse.body().string());
            final JSONObject data = body.optJSONObject("data");
            if (data == null) {
                return Collections.emptyList();
            }
            final JSONObject pages = data.optJSONObject("pages");
            if (pages == null) {
                return Collections.emptyList();
            }
            final JSONArray items = pages.optJSONArray("data");
            if (items == null || items.length() == 0) {
                return Collections.emptyList();
            }
            final List<Long> pageIds = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                pageIds.add(items.getJSONObject(i).getLong("id"));
            }
            LOGGER.info("[purgeAllPages] Fetched {} page id(s) from first result page", pageIds.size());
            return pageIds;
        }
    }

    private int deleteAllPages(List<Long> pageIds, String baseUrl, String projectId, int batchSize, ExecutorService batchExecutor) {
        final int total = pageIds.size();
        final AtomicInteger deleted = new AtomicInteger(0);
        for (int batchStart = 0; batchStart < total; batchStart += batchSize) {
            final int batchEnd = Math.min(batchStart + batchSize, total);
            final List<Long> batch = pageIds.subList(batchStart, batchEnd);
            final int batchNumber = batchStart / batchSize + 1;
            LOGGER.info("[purgeAllPages] Starting batch {} — pages {}-{} of {}",
                    batchNumber, batchStart + 1, batchEnd, total);
            final List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (Long pageId : batch) {
                futures.add(CompletableFuture.runAsync(
                        () -> deleteOnePage(pageId, baseUrl, projectId, deleted), batchExecutor));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            LOGGER.info("[purgeAllPages] Batch {} complete — {}/{} page(s) deleted so far",
                    batchNumber, deleted.get(), total);
        }
        return deleted.get();
    }

    private void deleteOnePage(Long pageId, String baseUrl, String projectId, AtomicInteger deleted) {
        final Request delRequest = new Request.Builder()
                .url(String.format("%s/projects/%s/pages/%s", baseUrl, projectId, pageId))
                .delete()
                .addHeader(HEADER_ACCEPT, MEDIA_TYPE_JSON)
                .addHeader(HEADER_AUTHORIZATION, BEARER_PREFIX + customGptConfig.getCustomGptToken())
                .build();
        try (Response delResponse = customGptClient.newCall(delRequest).execute()) {
            if (delResponse.isSuccessful()) {
                LOGGER.info("[purgeAllPages] Deleted page {}", pageId);
                deleted.incrementAndGet();
            } else {
                LOGGER.warn("[purgeAllPages] Failed to delete page {} (HTTP {})", pageId, delResponse.code());
            }
        } catch (IOException e) {
            LOGGER.warn("[purgeAllPages] Error deleting page {}: {}", pageId, e.getMessage());
        }
    }
}
