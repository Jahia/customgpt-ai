package org.jahia.community.modules.customgpt.service;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.OkHttpClient;
import java.util.concurrent.ExecutorService;
import org.jahia.api.settings.SettingsBean;
import org.jahia.api.templates.JahiaTemplateManagerService;
import org.jahia.community.modules.customgpt.indexer.listener.IndexOperations;
import org.jahia.community.modules.customgpt.indexer.listener.IndexerJCRListener;
import org.jahia.community.modules.customgpt.settings.Config;
import org.jahia.community.modules.customgpt.settings.NotConfiguredException;
import org.jahia.services.templates.TemplatePackageRegistry;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.event.EventHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for {@link Service#stop()}.
 *
 * <p>Context (JAHIACOM-1675): {@code JCRObservationManager} stores its listeners in a JVM-wide {@code static} list
 * and removes them by instance identity, while {@code TemplatePackageRegistry} de-duplicates them by
 * {@code Class} identity. A listener that {@code stop()} fails to unregister therefore survives its own bundle:
 * it keeps receiving publication events from an invalidated classloader, and neither a redeploy nor a wiring
 * refresh can evict it - only a restart of the node can. These tests pin down the two properties of {@code stop()}
 * that prevent that: the listener is detached first, and no single failing step can skip it.
 *
 * <p>{@code Service} is instantiated through its compiler-generated no-arg constructor and its collaborators are
 * injected reflectively, so no OSGi container or JCR repository is needed.
 */
public class ServiceShutdownTest {

    private Service service;
    private SettingsBean settingsBean;
    private TemplatePackageRegistry templatePackageRegistry;
    private IndexerJCRListener listener;
    private ServiceRegistration<EventHandler> eventHandlerRegistration;
    private ExecutorService executor;
    private ExecutorService executorFullIndexation;
    private ExecutorService executorNThreads;
    private OkHttpClient customGptClient;
    private OkHttpClient jahiaClient;
    private IndexService indexService;

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() throws Exception {
        service = Service.class.getDeclaredConstructor().newInstance();

        settingsBean = mock(SettingsBean.class);
        when(settingsBean.isProcessingServer()).thenReturn(true);

        templatePackageRegistry = mock(TemplatePackageRegistry.class);
        final JahiaTemplateManagerService templateManager = mock(JahiaTemplateManagerService.class);
        when(templateManager.getTemplatePackageRegistry()).thenReturn(templatePackageRegistry);

        listener = mock(IndexerJCRListener.class);
        // Wire the collaborators registerJcrListeners() needs, so that a listener registration would genuinely
        // succeed in this fixture. Without them init() NPEs inside getNodeTypes() and the disposed-latch test
        // below would pass for the wrong reason.
        indexService = mock(IndexService.class);
        when(indexService.getIndexedMainResourceNodeTypes()).thenReturn(Collections.singleton("jnt:page"));
        when(indexService.getIndexedSubNodeTypes()).thenReturn(Collections.emptySet());
        eventHandlerRegistration = mock(ServiceRegistration.class);
        executor = mock(ExecutorService.class);
        executorFullIndexation = mock(ExecutorService.class);
        executorNThreads = mock(ExecutorService.class);

        set("settingsBean", settingsBean);
        set("jahiaTemplateManagerService", templateManager);
        set("jcrListenerLive", listener);
        set("eventHandlerServiceRegistration", eventHandlerRegistration);
        set("executor", executor);
        set("executorFullIndexation", executorFullIndexation);
        set("executorNThreads", executorNThreads);
        set("journalEventReaderEnabled", false);
        set("indexService", indexService);
        final Config config = mock(Config.class);
        // init() builds the OkHttp clients after registering the listener; RateLimitInterceptor rejects 0.
        when(config.getRateLimitRequestsPerSecond()).thenReturn(1);
        set("customGptConfig", config);
        // Without these the client teardown short-circuits on null and the whole path goes untested.
        customGptClient = new OkHttpClient();
        jahiaClient = new OkHttpClient();
        set("customGptClient", customGptClient);
        set("jahiaClient", jahiaClient);
    }

    @SuppressWarnings("squid:S1172")
    private void set(String fieldName, Object value) throws Exception {
        final Field field = Service.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(service, value);
    }

    private Object get(String fieldName) throws Exception {
        final Field field = Service.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(service);
    }

    /**
     * The event handler is withdrawn first so no OSGi event can re-enter {@code init()} mid-teardown, and the
     * listener leaves the JVM-static observation registry before anything slower can fail or block.
     */
    @Test
    public void stop_withdrawsEventHandlerThenListenerBeforeShuttingDownExecutors() {
        service.stop();

        final InOrder order = inOrder(eventHandlerRegistration, templatePackageRegistry,
                executorFullIndexation, executor, executorNThreads);
        order.verify(eventHandlerRegistration).unregister();
        order.verify(templatePackageRegistry).handleJCREventListener(listener, false);
        // Every pool shutdown must follow the de-registration: each one can block for the full graceful window
        // and can throw, so any of them moving ahead of it reopens the stranding window.
        order.verify(executorFullIndexation).shutdown();
        order.verify(executor).shutdown();
        order.verify(executorNThreads).shutdown();
    }

    /**
     * The HTTP clients must be torn down only after every pool has had its graceful window. Cancelling earlier
     * aborts an in-flight DELETE-then-re-add and removes a page from the customer's CustomGPT project without
     * recreating it; and this teardown is component-wide, so it must not be reachable from the per-pool helper,
     * which also runs during normal operation on a caller-local pool.
     */
    @Test
    public void stop_closesHttpClientsOnlyAfterEveryPoolHasBeenShutDown() {
        // Record whether the clients were already dead at the moment the LAST pool was asked to shut down.
        // Asserting only the end state would pass even if the clients had been closed first, which is precisely
        // the ordering that aborts an in-flight DELETE-then-re-add.
        final AtomicBoolean clientsClosedBeforeLastPool = new AtomicBoolean();
        doAnswer(invocation -> {
            clientsClosedBeforeLastPool.set(customGptClient.dispatcher().executorService().isShutdown());
            return null;
        }).when(executorNThreads).shutdown();

        service.stop();

        assertThat(clientsClosedBeforeLastPool).isFalse();
        assertThat(customGptClient.dispatcher().executorService().isShutdown()).isTrue();
        assertThat(jahiaClient.dispatcher().executorService().isShutdown()).isTrue();
    }

    /** A stranded listener driving a full re-index must be rejected too, not just the incremental path. */
    @Test
    public void produceAsynchronousFullIndexation_afterStop_isRejected() {
        service.stop();

        assertThatThrownBy(() -> service.produceAsynchronousFullIndexation(new IndexOperations()))
                .isInstanceOf(RejectedExecutionException.class);
    }

    /** Likewise the site-indexation path, which runs on its own pool. */
    @Test
    public void produceSiteAsynchronousIndexations_afterStop_isRejected() {
        service.stop();

        assertThatThrownBy(() -> service.produceSiteAsynchronousIndexations("/sites/acme", new IndexOperations()))
                .isInstanceOf(RejectedExecutionException.class);
    }

    /**
     * The framework unregisters a bundle's services while it is stopping, so the event handler registration may
     * already be gone by the time {@code stop()} runs. That must not cost us the listener de-registration.
     */
    @Test
    public void stop_unregistersJcrListener_evenWhenEventHandlerUnregistrationThrows() {
        doThrow(new IllegalStateException("Service already unregistered."))
                .when(eventHandlerRegistration).unregister();

        assertThatCode(() -> service.stop()).doesNotThrowAnyException();

        verify(templatePackageRegistry).handleJCREventListener(listener, false);
    }

    /**
     * The listener de-registration is itself wrapped, so a failure there must not strand the executors and HTTP
     * clients cleaned up after it. This is the one {@code runQuietly} wrapper that ordering alone cannot cover.
     */
    @Test
    public void stop_continuesCleanup_whenListenerDeregistrationThrows() {
        doThrow(new IllegalStateException("registry unavailable"))
                .when(templatePackageRegistry).handleJCREventListener(any(), eq(false));

        assertThatCode(() -> service.stop()).doesNotThrowAnyException();

        verify(executor).shutdown();
        verify(executorNThreads).shutdown();
    }

    /**
     * A {@code LinkageError} is as likely as a {@code RuntimeException} while the bundle is going down, and must
     * not abort the remaining cleanup either.
     */
    @Test
    public void stop_continuesCleanup_whenAStepThrowsLinkageError() {
        doThrow(new NoClassDefFoundError("org/jahia/community/modules/customgpt/Whatever"))
                .when(eventHandlerRegistration).unregister();

        assertThatCode(() -> service.stop()).doesNotThrowAnyException();

        verify(templatePackageRegistry).handleJCREventListener(listener, false);
        verify(executor).shutdown();
    }

    /** A failure while shutting down one pool must not strand the pools and clients cleaned up after it. */
    @Test
    public void stop_continuesCleanup_whenAnExecutorShutdownThrows() {
        doThrow(new RuntimeException("pool is wedged")).when(executorFullIndexation).shutdown();

        assertThatCode(() -> service.stop()).doesNotThrowAnyException();

        verify(templatePackageRegistry).handleJCREventListener(listener, false);
        verify(executor).shutdown();
        verify(executorNThreads).shutdown();
    }

    /** {@code stop()} may be invoked more than once; the second pass must be a no-op rather than a throw. */
    @Test
    public void stop_isIdempotent() throws Exception {
        service.stop();
        assertThatCode(() -> service.stop()).doesNotThrowAnyException();

        // The registration is cleared on the first pass, so it is never unregistered twice.
        verify(eventHandlerRegistration, times(1)).unregister();
        // Likewise the listener: unregisterJcrListeners() nulls the field once it has detached it.
        verify(templatePackageRegistry, times(1)).handleJCREventListener(any(), eq(false));
        assertThat(get("jcrListenerLive")).isNull();
    }

    /**
     * Regression guard for the shape of this very fix: de-registration must not be conditional on anything other
     * than "a listener is registered". A guard on {@code settingsBean} would silently skip the one step that,
     * if missed, strands the listener for the life of the JVM.
     */
    @Test
    public void stop_unregistersJcrListener_evenWhenSettingsBeanIsUnavailable() throws Exception {
        set("settingsBean", null);

        assertThatCode(() -> service.stop()).doesNotThrowAnyException();

        verify(templatePackageRegistry).handleJCREventListener(listener, false);
        assertThat(get("jcrListenerLive")).isNull();
    }

    /**
     * An OSGi event still in flight when the component is deactivated must not be able to register a fresh
     * listener from a bundle on its way out - that is the same leak by another door.
     */
    @Test
    public void init_afterStop_doesNotRegisterAnotherListener() throws Exception {
        // Sanity-check the fixture first: without the latch, init() really would register. Otherwise the
        // assertions below would hold for any reason at all.
        invokeInit();
        verify(templatePackageRegistry).handleJCREventListener(any(), eq(true));

        service.stop();
        clearInvocations(templatePackageRegistry);

        invokeInit();

        verify(templatePackageRegistry, never()).handleJCREventListener(any(), eq(true));
        assertThat(get("jcrListenerLive")).isNull();
    }

    /** A stranded listener calling into a stopped service must be rejected, not handed a brand-new thread pool. */
    @Test
    public void produceAsynchronousOperations_afterStop_isRejected() {
        service.stop();

        assertThatThrownBy(() -> service.produceAsynchronousOperations(new IndexOperations()))
                .isInstanceOf(RejectedExecutionException.class);
    }

    /**
     * A listener whose node-type filter came back empty matches no event in Jahia, so registering it would leave
     * the module reporting healthy while indexing nothing. Registration must be refused instead.
     */
    @Test
    public void registerJcrListeners_refusesAListenerWithNoNodeTypeFilter() throws Exception {
        doThrow(new NotConfiguredException("not configured")).when(indexService).getIndexedMainResourceNodeTypes();
        doThrow(new NotConfiguredException("not configured")).when(indexService).getIndexedSubNodeTypes();
        set("jcrListenerLive", null);

        invokeInit();

        verify(templatePackageRegistry, never()).handleJCREventListener(any(), eq(true));
        assertThat(get("jcrListenerLive")).isNull();
    }

    /** Once the configuration arrives, a refused registration has to be recoverable. */
    @Test
    public void refreshJcrListeners_registersAfterConfigurationBecomesAvailable() throws Exception {
        doThrow(new NotConfiguredException("not configured")).when(indexService).getIndexedMainResourceNodeTypes();
        set("jcrListenerLive", null);
        invokeInit();
        verify(templatePackageRegistry, never()).handleJCREventListener(any(), eq(true));

        // Configuration arrives.
        doReturn(Collections.singleton("jnt:page")).when(indexService).getIndexedMainResourceNodeTypes();
        service.refreshJcrListeners();

        verify(templatePackageRegistry).handleJCREventListener(any(), eq(true));
        assertThat(get("jcrListenerLive")).isNotNull();
    }

    /** The filter is snapshotted at registration, so a change to the indexed types must re-register. */
    @Test
    public void refreshJcrListeners_reRegistersWhenTheIndexedNodeTypesChange() throws Exception {
        set("jcrListenerLive", null);
        invokeInit();
        clearInvocations(templatePackageRegistry);

        doReturn(new LinkedHashSet<>(Arrays.asList("jnt:page", "jnt:file")))
                .when(indexService).getIndexedMainResourceNodeTypes();
        service.refreshJcrListeners();

        verify(templatePackageRegistry).handleJCREventListener(any(), eq(true));
    }

    /** An unchanged configuration must not churn the registration. */
    @Test
    public void refreshJcrListeners_isANoOpWhenNothingChanged() throws Exception {
        set("jcrListenerLive", null);
        invokeInit();
        clearInvocations(templatePackageRegistry);

        service.refreshJcrListeners();

        verify(templatePackageRegistry, never()).handleJCREventListener(any(), eq(true));
    }

    private void invokeInit() throws Exception {
        final Method init = Service.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(service);
    }
}
