package org.jahia.community.modules.customgpt.service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
import org.jahia.osgi.FrameworkService;
import org.jahia.services.content.*;
import org.jahia.services.events.JournalEventReader;
import org.jahia.services.query.QueryWrapper;
import org.jahia.services.scheduler.BackgroundJob;
import org.jahia.services.scheduler.SchedulerService;
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
    private static final String ADDED_TO_THE_REGISTRY = "Task {}{} is added to the registry";
    private static final String CUSTOM_GPT_SITE_INDEXATION = "CustomGpt site indexation";
    private static final String REGISTER_EVENT = "org/jahia/modules/sam/TaskRegistryService/REGISTER";
    private static final String REMOVED_FROM_REGISTRY = "Task {} {} is removed from registry";
    private static final String UNREGISTER_EVENT = "org/jahia/modules/sam/TaskRegistryService/UNREGISTER";
    private static final String INDEXATION_FAILED_DUE_TO_CONFIGURATION_ISSUES = "Indexation failed due to configuration issues: {}";
    private static final String PROP_INDEXATION_END = "customGptIndexationEnd";
    private static final String PROP_INDEXATION_SCHEDULED = "customGptIndexationScheduled";
    private static final String PROP_INDEXATION_START = "customGptIndexationStart";
    private static final String RECREATE_LOG = "Recreate Log";
    private static final int N_THREADS = 2;
    private static final String HEADER_AUTHORIZATION = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String HEADER_ACCEPT = "accept";
    private static final String MEDIA_TYPE_JSON = "application/json";
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
    private boolean initialized;
    private boolean journalEventReaderEnabled;
    private int retryOnConflict;
    private OkHttpClient customGptClient;
    private OkHttpClient jahiaClient;
    
    @Activate
    public void activate(BundleContext bundleContext) {
        this.bundleContext = bundleContext;
        @SuppressWarnings("java:S1149")
        final Dictionary<String, Object> topics = new Hashtable<>();
        topics.put(EventConstants.EVENT_TOPIC, new String[]{CustomGptConstants.EVENT_TOPIC});
        bundleContext.registerService(EventHandler.class.getName(), this, topics);
        start();
    }
    
    @Deactivate
    public void deactivate() {
        stop();
    }
    
    @Reference(service = Config.class)
    public void setCustomGptConfig(Config customGptConfig) {
        this.customGptConfig = customGptConfig;
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
            } catch (Exception e) {
                LOGGER.error("Indexation failed due to: {}", e.getMessage(), e);
            }
        }, executorFullIndexation);
    }
    
    private void restartExecutor() {
        if (executor.isShutdown() || executor.isTerminated()) {
            LOGGER.warn("Executor is shutdown or terminated, starting a new one");
            executor = Executors.newFixedThreadPool(1);
        }
    }
    
    private void restartExecutorFullIndexation() {
        if (executorFullIndexation.isShutdown() || executorFullIndexation.isTerminated()) {
            LOGGER.warn("ExecutorFullIndexation is shutdown or terminated, starting a new one");
            executorFullIndexation = Executors.newFixedThreadPool(1);
        }
    }
    
    private void restartExecutorNThreads() {
        if (executorNThreads.isShutdown() || executorNThreads.isTerminated()) {
            LOGGER.warn("Executor with {} threads is shutdown or terminated, starting a new one", N_THREADS);
            executorNThreads = Executors.newFixedThreadPool(N_THREADS);
        }
    }
    
    private ExecutorService executor = Executors.newFixedThreadPool(1);
    private ExecutorService executorFullIndexation = Executors.newFixedThreadPool(1);
    private ExecutorService executorNThreads = Executors.newFixedThreadPool(N_THREADS);
    
    public void produceAsynchronousOperations(IndexOperations... operations) {
        restartExecutor();
        final CompletableFuture<Void>[] completableFuture = new CompletableFuture[operations.length];
        int i = 0;
        for (IndexOperations operation : operations) {
            completableFuture[i++] = CompletableFuture.supplyAsync(getPerformIndexationSupplier(operation), executor);
        }
        CompletableFuture.allOf(completableFuture);
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
        } catch (RepositoryException e) {
            LOGGER.error("Failed to record indexation start time: {}", e.getMessage());
        }
        CompletableFuture.allOf(completableFuture).whenCompleteAsync((unused, throwable) -> {
            try {
                updateIndexationTime(sitePath, PROP_INDEXATION_END, new GregorianCalendar());
            } catch (RepositoryException e) {
                LOGGER.error("Failed to record indexation end time: {}", e.getMessage());
            }
        });
    }
    
    private Supplier<Void> getPerformIndexationSupplier(IndexOperations operations) {
        return () -> {
            try {
                performIndexation(operations);
            } catch (Exception e) {
                LOGGER.error("Indexation failed due to: {}", e.getMessage(), e);
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
        if (acceptablePathToIndex(op.getNodePath())) {
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
        if (acceptablePathToIndex(op.getNodePath())) {
            customGptIndexer.addNodePathToMove(op.getSourcePath(), op.getNodePath());
        }
        return customGptIndexer;
    }

    private Indexer handleSiteIndex(IndexOperations operations, Indexer customGptIndexer,
            IndexOperations.CustomGptIndexOperation op) throws RepositoryException, NotConfiguredException {
        preIndexOperationHandler(operations);
        customGptIndexer = initIndexer(customGptIndexer);
        if (acceptablePathToIndex(op.getNodePath())) {
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
        if (acceptablePathToIndex(path)) {
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
    
    private synchronized void registerJcrListeners() {
        unregisterJcrListeners();
        
        LOGGER.info("Registering JCR listeners");
        
        jcrListenerLive = new IndexerJCRListener(true, this, customGptConfig);
        
        if (journalEventReaderEnabled) {
            journalEventReader.replayMissedEvents(jcrListenerLive, journalEventReaderKey);
            journalEventReader.rememberLastProcessedJournalRevision(journalEventReaderKey);
        }
        
        handleJCREventListener(jcrListenerLive, true);
    }
    
    private synchronized void unregisterJcrListeners() {
        if (jcrListenerLive != null) {
            LOGGER.info("Unregistering JCR listener for live workspace");
            
            handleJCREventListener(jcrListenerLive, false);
            jcrListenerLive = null;
        }
    }
    
    public void setJahiaTemplateManagerService(JahiaTemplateManagerService jahiaTemplateManagerService) {
        this.jahiaTemplateManagerService = jahiaTemplateManagerService;
    }
    
    private void init() {
        if (!initialized) {
            LOGGER.info("Starting service...");
            if (settingsBean.isProcessingServer()) {
                registerJcrListeners();
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
                        .build();
                customGptClient = new OkHttpClient.Builder()
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
            }
            initialized = true;
            LOGGER.info("...service started");
        }
    }
    
    public void start() {
        registerEventHandler();
        init();
    }
    
    public void stop() {
        shutdownAndAwaitTermination(executorFullIndexation);
        shutdownAndAwaitTermination(executor);
        shutdownAndAwaitTermination(executorNThreads);
        unregisterEventHandler();
        if (settingsBean.isProcessingServer()) {
            unregisterJcrListeners();
            if (journalEventReaderEnabled) {
                journalEventReader.rememberLastProcessedJournalRevision(journalEventReaderKey);
            }
        }
        closeHttpClient(customGptClient);
        closeHttpClient(jahiaClient);
        initialized = false;
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
            if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
            // Wait a while for tasks to respond to being cancelled
            if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
                LOGGER.error("Pool did not terminate {}", pool);
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
    
    public int getRetryOnConflict() {
        return retryOnConflict;
    }
    
    public void setRetryOnConflict(int retryOnConflict) {
        this.retryOnConflict = retryOnConflict;
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

        if ((CustomGptConstants.EVENT_TYPE_TRANSPORT_CLIENT_SERVICE_AVAILABLE.equals(type)
                || CustomGptConstants.EVENT_TYPE_CONFIG_UPDATED.equals(type)
                || CustomGptConstants.EVENT_TYPE_CONFIG_UPDATED_REQUIRE_REINDEX.equals(type))
                && customGptConfig.isConfigured()) {
            init();
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
            java.util.Dictionary<String, Object> props = config.getProperties();
            if (props == null) {
                props = new java.util.Hashtable<>();
            }
            props.put("org.jahia.community.modules.customgpt.scheduleJobASAP", Boolean.FALSE);
            config.update(props);
            LOGGER.info("Reset scheduleJobASAP to false after scheduling indexation jobs");
        } catch (Exception e) {
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
    
    private void unregisterEventHandler() {
        if (eventHandlerServiceRegistration != null) {
            LOGGER.info("Unregistering Event Handler");
            eventHandlerServiceRegistration.unregister();
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
    
    private void updateIndexationTime(String path, String property, Calendar date) throws RepositoryException {
        JCRTemplate.getInstance().doExecuteWithSystemSession(new JCRCallback<Object>() {
            @Override
            public Object doInJCR(JCRSessionWrapper session) throws RepositoryException {
                final JCRNodeWrapper node = session.getNode(path);
                node.setProperty(property, date);
                session.save();
                LOGGER.info("Site {} indexation has {} at {}", path, (property.equals(PROP_INDEXATION_START) ? "started" : "ended"), date.toInstant());
                return null;
            }
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
    public String getProjectName() {
        final String projectId = customGptConfig.getCustomGptProjectId();
        if (projectId == null || projectId.isEmpty()) {
            return null;
        }
        String baseUrl = customGptConfig.getCustomGptApiBaseUrl();
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = CustomGptConstants.DEFAULT_CUSTOM_GPT_API_BASE_URL;
        }
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
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
            final JSONObject body = new JSONObject(response.body().string());
            final JSONObject data = body.optJSONObject("data");
            return data != null ? data.optString("project_name", null) : null;
        } catch (IOException e) {
            LOGGER.warn("Error fetching CustomGPT project name: {}", e.getMessage());
            return null;
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
    public int purgeAllPages() throws IOException {
        final String projectId = customGptConfig.getCustomGptProjectId();
        LOGGER.info("[purgeAllPages] Starting purge for project {}", projectId);

        String baseUrl = customGptConfig.getCustomGptApiBaseUrl();
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = CustomGptConstants.DEFAULT_CUSTOM_GPT_API_BASE_URL;
        }
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        int batchSize;
        try {
            batchSize = customGptConfig.getBulkOperationsBatchSize();
        } catch (Exception e) {
            batchSize = 10;
        }

        final String firstPageUrl = String.format("%s/projects/%s/pages", baseUrl, projectId);
        int totalDeleted = 0;
        int round = 1;

        while (true) {
            LOGGER.info("[purgeAllPages] Round {}: fetching first result page from CustomGPT", round);
            final List<Long> pageIds = fetchOnePage(firstPageUrl);
            if (pageIds.isEmpty()) {
                break;
            }
            LOGGER.info("[purgeAllPages] Round {}: {} page(s) to delete (batch size {})", round, pageIds.size(), batchSize);
            totalDeleted += deleteAllPages(pageIds, baseUrl, projectId, batchSize);
            LOGGER.info("[purgeAllPages] Round {} complete — {} page(s) deleted so far", round, totalDeleted);
            round++;
        }

        LOGGER.info("[purgeAllPages] Purge complete — deleted {} page(s) from project {}", totalDeleted, projectId);
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

    private int deleteAllPages(List<Long> pageIds, String baseUrl, String projectId, int batchSize) {
        final int total = pageIds.size();
        final AtomicInteger deleted = new AtomicInteger(0);
        // Cap threads to the rate limit so we never create more concurrent callers than tokens-per-second
        final int threadCount = Math.min(batchSize, customGptConfig.getRateLimitRequestsPerSecond());
        final ExecutorService batchExecutor = Executors.newFixedThreadPool(threadCount);
        try {
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
        } finally {
            shutdownAndAwaitTermination(batchExecutor);
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
