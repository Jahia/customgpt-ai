package org.jahia.community.modules.customgpt.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.query.Query;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;
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
import org.jahia.community.modules.customgpt.util.AuthorizationInterceptor;
import org.jahia.community.modules.customgpt.util.RateLimitInterceptor;
import org.jahia.osgi.FrameworkService;
import org.jahia.services.content.*;
import org.jahia.services.events.JournalEventReader;
import org.jahia.services.query.QueryWrapper;
import org.jahia.services.scheduler.BackgroundJob;
import org.jahia.services.scheduler.SchedulerService;
import org.jahia.services.usermanager.JahiaUser;
import org.jahia.services.usermanager.JahiaUserManagerService;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
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

@Component(service = {Service.class}, immediate = true)
public class Service implements EventHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(Service.class);
    private static final Pattern SITE_MATCHER = Pattern.compile("\\/sites\\/.+");
    private static final String ADDED_TO_THE_REGISTRY = "Task {}{} is added to the registry";
    private static final String CUSTOM_GPT_SITE_INDEXATION = "CustomGpt site indexation";
    private static final String REGISTER_EVENT = "org/jahia/modules/sam/TaskRegistryService/REGISTER";
    private static final String REMOVED_FROM_REGISTRY = "Task {} {} is removed from registry";
    private static final String UNREGISTER_EVENT = "org/jahia/modules/sam/TaskRegistryService/UNREGISTER";
    private static final String INDEXATION_FAILED_DUE_TO_ABSENT_CONNECTION = "Indexation failed due to absent connection: {}";
    private static final String INDEXATION_FAILED_DUE_TO_CONFIGURATION_ISSUES = "Indexation failed due to configuration issues: {}";
    private static final String INDEXATION_FAILED_DUE_TO_THREAD_INTERRUPTION = "Indexation failed due to thread interruption: {}";
    private static final String PROP_INDEXATION_END = "customGptIndexationEnd";
    private static final String PROP_INDEXATION_SCHEDULED = "customGptIndexationScheduled";
    private static final String PROP_INDEXATION_START = "customGptIndexationStart";
    private static final String RECREATE_LOG = "Recreate Log";
    private static final int N_THREADS = 2;
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

    private enum Alias {
        READ, WRITE
    }

    @Activate
    public void activate(BundleContext bundleContext) {
        this.bundleContext = bundleContext;
        final Dictionary topics = new Hashtable();
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

    @Reference(service = JournalEventReader.class)
    public void setJournalEventReader(JournalEventReader journalEventReader) {
        journalEventReaderKey = customGptConfig.getJournalEventReaderKey();
        journalEventReaderEnabled = customGptConfig.isReplayMissedJcrEvents();
        this.journalEventReader = journalEventReader;
    }

    @Reference(service = IndexService.class)
    public void setIndexService(IndexService indexService) {
        this.indexService = indexService;
        ((FileIndexBuilder) this.indexService.getIndexBuilder(CustomGptConstants.IndexType.FILE)).setCustomGptService(this);
        ((ContentIndexBuilder) this.indexService.getIndexBuilder(CustomGptConstants.IndexType.CONTENT)).setCustomGptService(this);
    }

    private Indexer createIndexer() throws RepositoryException {
        final Indexer customGptIndexer = new Indexer(this, customGptConfig);
        return customGptIndexer;
    }

    public Set<String> getNodePathsToIndex(JCRNodeWrapper node) throws RepositoryException, NotConfiguredException {
        return indexService.getNodePathsToIndex(node);
    }

    public void addIndexRequests(
            JCRNodeWrapper node, String language, Set<CustomGptRequest> requests) throws RepositoryException, NotConfiguredException {
        indexService.addIndexRequests(node, language, requests);
    }

    public JCRNodeWrapper getParentDisplayableNode(JCRNodeWrapper nestedNode, String index) throws NotConfiguredException {
        return indexService.getParentDisplayableNode(nestedNode, index);
    }

    public Set<String> getIndexedMainResourceNodeTypes() throws NotConfiguredException {
        return indexService.getIndexedMainResourceNodeTypes();
    }

    public Set<String> getIndexedSubNodeTypes() throws NotConfiguredException {
        return indexService.getIndexedSubNodeTypes();
    }

    public JobDetail reIndexUsingJob(String siteKey) {
        return reIndexUsingJob(siteKey, false);
    }

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
        } else {
            final Calendar indexationStartLastRun = hasIndexationStart
                    ? jcrNodeWrapper.getProperty(Service.PROP_INDEXATION_START).getDate() : null;
            if (!jcrNodeWrapper.hasProperty(Service.PROP_INDEXATION_SCHEDULED)) {
                jcrNodeWrapper.setProperty(Service.PROP_INDEXATION_SCHEDULED, scheduled);
            }
            final Calendar indexationScheduledLastRun = jcrNodeWrapper.getProperty(Service.PROP_INDEXATION_SCHEDULED).getDate();
            if (force || !hasIndexationStart || (indexationScheduledLastRun.before(indexationStartLastRun) && scheduled.after(indexationStartLastRun))) {

                final Calendar indexationEndLastRun = hasIndexationEnd
                        ? jcrNodeWrapper.getProperty(Service.PROP_INDEXATION_END).getDate() : null;
                if (force) {
                    if (hasIndexationStart) {
                        jcrNodeWrapper.getProperty(Service.PROP_INDEXATION_START).remove();
                    }
                    if (hasIndexationEnd) {
                        jcrNodeWrapper.getProperty(Service.PROP_INDEXATION_END).remove();
                    }
                }
                if (force || !hasIndexationStart || (indexationEndLastRun != null && scheduled.after(indexationEndLastRun) && indexationEndLastRun.after(indexationStartLastRun))) {
                    jcrNodeWrapper.setProperty(Service.PROP_INDEXATION_SCHEDULED, scheduled);
                }
            }
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

    public void reIndexUsingJob() throws SchedulerException, RepositoryException {
        for (String site : getIndexedSites().keySet()) {
            reIndexUsingJob(site);
        }
    }

    private Set<String> getSitePathsToIndex() {
        final Collection<Site> siteNodes = getIndexedSites().values();
        final Set<String> sitePathsToBeIndexed = new HashSet<>();
        if (!siteNodes.isEmpty()) {

            for (Site site : siteNodes) {
                if (site.getIndexationScheduled() != null
                        && ((site.getIndexationEnd() == null && site.getIndexationStart() == null)
                        || (site.getIndexationScheduled().after(site.getIndexationStart())
                        && site.getIndexationScheduled().after(site.getIndexationEnd())
                        && site.getIndexationStart().before(site.getIndexationEnd())))) {
                    sitePathsToBeIndexed.add(site.getPath());
                }
            }
        }
        return sitePathsToBeIndexed;
    }

    public void produceAsynchronousFullIndexation(IndexOperations operations) {
        restartExecutorFullIndexation();
        CompletableFuture.runAsync(() -> {
            try {
                performIndexation(operations);
            } catch (InterruptedException e) {
                LOGGER.error(INDEXATION_FAILED_DUE_TO_THREAD_INTERRUPTION, e.getMessage());
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LOGGER.error("Indexation failed due to: {}", e.getMessage(), e);
            }
        }, executorFullIndexation);
    }

    private static void restartExecutor() {
        if (executor.isShutdown() || executor.isTerminated()) {
            LOGGER.warn("Executor is shutdown or terminated, starting a new one");
            executor = Executors.newSingleThreadExecutor();
        }
    }

    private static void restartExecutorFullIndexation() {
        if (executorFullIndexation.isShutdown() || executorFullIndexation.isTerminated()) {
            LOGGER.warn("ExecutorFullIndexation is shutdown or terminated, starting a new one");
            executorFullIndexation = Executors.newSingleThreadExecutor();
        }
    }

    private static void restartExecutorNThreads() {
        if (executorNThreads.isShutdown() || executorNThreads.isTerminated()) {
            LOGGER.warn("Executor with {} threads is shutdown or terminated, starting a new one", N_THREADS);
            executorNThreads = Executors.newFixedThreadPool(N_THREADS);
        }
    }

    private static ExecutorService executor = Executors.newSingleThreadExecutor();
    private static ExecutorService executorFullIndexation = Executors.newSingleThreadExecutor();
    private static ExecutorService executorNThreads = Executors.newFixedThreadPool(N_THREADS);

    public void produceAsynchronousOperations(IndexOperations... operations) throws NotConfiguredException {
        restartExecutor();
        final CompletableFuture<Void>[] completableFuture = new CompletableFuture[operations.length];
        int i = 0;
        for (IndexOperations operation : operations) {
            completableFuture[i++] = CompletableFuture.supplyAsync(getPerformIndexationSupplier(operation), executor);
        }
        CompletableFuture.allOf(completableFuture);
    }

    public void produceSiteAsynchronousIndexations(String sitePath, IndexOperations... operations) throws NotConfiguredException {
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
            } catch (InterruptedException e) {
                LOGGER.error(INDEXATION_FAILED_DUE_TO_THREAD_INTERRUPTION, e.getMessage());
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LOGGER.error("Indexation failed due to: {}", e.getMessage(), e);
            }
            return null;
        };
    }

    private void performIndexation(IndexOperations operations)
            throws RepositoryException, NotConfiguredException, InterruptedException, IOException {
        if (operations == null || operations.isEmpty()) {
            // No operations to queueRequests
            LOGGER.error("Operations is empty, exiting performIndexation");
            return;
        }

        indexAllOperations(operations);
    }

    private void indexAllOperations(IndexOperations operations)
            throws RepositoryException, NotConfiguredException, InterruptedException, IOException {
        Indexer customGptIndexer = null;
        try {
            final Collection<Site> indexedSites = getIndexedSites(getSystemSession(JahiaUserManagerService.getInstance().lookupRootUser().getJahiaUser(), null, null)).values();
            for (IndexOperations.CustomGptIndexOperation indexOperation : operations.getOperations()) {
                // Check that the path for the operation require indexation to avoid unwanted indexation
                customGptIndexer = indexOperation(operations, customGptIndexer, indexOperation, indexedSites);
            }

            if (customGptIndexer != null) {
                customGptIndexer.queueRequests(customGptClient, jahiaClient);
            }
        } catch (NotConfiguredException e) {
            LOGGER.error(INDEXATION_FAILED_DUE_TO_CONFIGURATION_ISSUES, e.getMessage(), e);
        }

    }

    // Complexity 11 instead of needed 10
    private Indexer indexOperation(IndexOperations operations, Indexer customGptIndexer,
            IndexOperations.CustomGptIndexOperation customGptIndexOperation, Collection<Site> indexedSites)
            throws RepositoryException, NotConfiguredException {
        try {
            final CustomGptOperationType opType = customGptIndexOperation.getType();
            switch (opType) {
                case NODE_INDEX:
                    customGptIndexer = initIndexer(customGptIndexer);
                    if (acceptablePathToIndex(customGptIndexOperation.getNodePath(), indexedSites)) {
                        indexNode(customGptIndexer, customGptIndexOperation);
                    }
                    break;
                case NODE_REMOVE:
                    customGptIndexer = initIndexer(customGptIndexer);
                    // we don't care of node remove under non indexed sites, since it's used by the RemoveSiteJob.
                    customGptIndexer.addNodeToDelete(customGptIndexOperation.getCustomGptPageId(), customGptIndexOperation.getNodePath());
                    break;
                case NODE_MOVE:
                    customGptIndexer = initIndexer(customGptIndexer);
                    if (acceptablePathToIndex(customGptIndexOperation.getNodePath(), indexedSites)) {
                        customGptIndexer.addNodePathToMove(customGptIndexOperation.getSourcePath(), customGptIndexOperation.getNodePath());
                    }
                    break;
                case SITE_INDEX:
                    preIndexOperationHandler(operations);
                    customGptIndexer = initIndexer(customGptIndexer);
                    if (acceptablePathToIndex(customGptIndexOperation.getNodePath(), indexedSites)) {
                        customGptIndexer.addSiteToIndex(customGptClient, jahiaClient, customGptIndexOperation.getNodePath());
                    }
                    postIndexOperationHandler(operations);
                    break;
                case TREE_INDEX: // re-index descendant nodes
                    LOGGER.info("Received a sub nodes index operation for following node {} in workspace live", customGptIndexOperation.getNodePath());
                    customGptIndexer = initIndexer(customGptIndexer);
                    final String path = customGptIndexOperation.getNodePath();
                    if (acceptablePathToIndex(path, indexedSites)) {
                        final JCRNodeWrapper node = customGptIndexer.getSystemSession().getNode(path);
                        indexNode(customGptIndexer, customGptIndexOperation);
                        customGptIndexer.addNodesToIndex(customGptClient, jahiaClient, node);
                    }
                    break;
                default:
                    break;
            }
        } catch (PathNotFoundException e) {
            // Skip the operation as its node no longer exists
            LOGGER.info("Did not find indexation path: {}", e.getMessage());
        }
        return customGptIndexer;
    }

    private void postIndexOperationHandler(IndexOperations operations) throws NotConfiguredException {
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

    private void preIndexOperationHandler(IndexOperations operations) throws NotConfiguredException {
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

    private Indexer initIndexer(Indexer customGptIndexer) throws RepositoryException {
        if (customGptIndexer == null) {
            customGptIndexer = createIndexer();
        }
        return customGptIndexer;
    }

    public int getPendingIndexationOperations() {
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
                final String jahiaCredential = Credentials.basic(customGptConfig.getJahiaUsername(), customGptConfig.getJahiaPassword(), StandardCharsets.UTF_8);
                jahiaClient = new OkHttpClient.Builder()
                        .addInterceptor(new AuthorizationInterceptor(jahiaCredential))
                        .build();
                customGptClient = new OkHttpClient.Builder()
                        .authenticator((route, response) -> {
                            if (response.request().header("Authorization") != null) {
                                return null;
                            }
                            return response.request().newBuilder()
                                    .addHeader("Authorization", String.format("Bearer %s", customGptConfig.getCustomGptToken()))
                                    .build();
                        })
                        .addInterceptor(new RateLimitInterceptor())
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
        initialized = false;
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

    @Override
    public void handleEvent(Event event) {
        final String type = (String) event.getProperty("type");
        LOGGER.info("Received event from topic {} of type {}", event.getTopic(), type);

        if ((CustomGptConstants.EVENT_TYPE_TRANSPORT_CLIENT_SERVICE_AVAILABLE.equals(type) || CustomGptConstants.EVENT_TYPE_CONFIG_UPDATED.equals(type))
                && customGptConfig.isConfigured()) {
            init();
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
                    String.format("SELECT * FROM [%s] AS site WHERE ISCHILDNODE(site, '%s') ORDER BY localname()", CustomGptConstants.MIX_INDEXABLE_SITE, CustomGptConstants.PATH_SITES),
                    Query.JCR_SQL2);
            for (JCRNodeWrapper jcrNodeWrapper : query.execute().getNodes()) {
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
                sites.put(site.getSiteKey(), site);
            }
            if (LOGGER.isDebugEnabled()) {
                sites.forEach((s, site) -> LOGGER.debug("Site {}, has props: start {}, end {}, scheduled {}", s,
                        site.getIndexationStart() != null ? site.getIndexationStart().toInstant() : "unset",
                        site.getIndexationEnd() != null ? site.getIndexationEnd().toInstant() : "unset",
                        site.getIndexationScheduled() != null ? site.getIndexationScheduled().toInstant() : "unset"));
            }
            return sites;
        } catch (RepositoryException e) {
            LOGGER.warn("Issue while fetching list of sites for CustomGpt", e);
            return Collections.emptyMap();
        }
    }

    public boolean acceptablePathToIndex(String path, Collection<Site> indexedSites) {
        return ((path.startsWith("/trash-") || SITE_MATCHER.matcher(path).matches()) && !path.endsWith(CustomGptConstants.PROP_CUSTOM_GPT_PAGE_ID) && !path.endsWith(Constants.JCR_MIXINTYPES) && !path.endsWith(Constants.JCR_LASTMODIFIED));
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
}
