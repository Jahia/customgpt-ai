package org.jahia.community.modules.customgpt.service;

import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import org.jahia.api.settings.SettingsBean;
import org.jahia.api.templates.JahiaTemplateManagerService;
import org.jahia.community.modules.customgpt.indexer.listener.IndexerJCRListener;
import org.jahia.services.templates.TemplatePackageRegistry;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.event.EventHandler;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    }

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

    /** The listener must leave the JVM-static observation registry before anything slower can fail or block. */
    @Test
    public void stop_unregistersJcrListenerBeforeShuttingDownExecutors() {
        service.stop();

        final InOrder order = inOrder(templatePackageRegistry, executorFullIndexation, executor, executorNThreads);
        order.verify(templatePackageRegistry).handleJCREventListener(listener, false);
        order.verify(executorFullIndexation).shutdown();
        order.verify(executor).shutdown();
        order.verify(executorNThreads).shutdown();
    }

    /**
     * The framework unregisters a bundle's services while it is stopping, so the event handler registration is
     * frequently already gone by the time {@code stop()} runs. That must not cost us the listener de-registration.
     */
    @Test
    public void stop_unregistersJcrListener_evenWhenEventHandlerUnregistrationThrows() {
        doThrow(new IllegalStateException("Service already unregistered."))
                .when(eventHandlerRegistration).unregister();

        assertThatCode(() -> service.stop()).doesNotThrowAnyException();

        verify(templatePackageRegistry).handleJCREventListener(listener, false);
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
        assertThatCode(() -> get("jcrListenerLive")).doesNotThrowAnyException();
    }

    /** On a browsing (non-processing) node no listener was ever registered, so none must be de-registered. */
    @Test
    public void stop_doesNotTouchJcrListeners_onNonProcessingNode() {
        when(settingsBean.isProcessingServer()).thenReturn(false);

        service.stop();

        verify(templatePackageRegistry, never()).handleJCREventListener(any(), eq(false));
        verify(executor).shutdown();
    }
}
