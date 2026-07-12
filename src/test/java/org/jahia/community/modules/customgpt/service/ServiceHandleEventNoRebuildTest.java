package org.jahia.community.modules.customgpt.service;

import java.lang.reflect.Field;
import okhttp3.OkHttpClient;
import org.jahia.community.modules.customgpt.CustomGptConstants;
import org.jahia.community.modules.customgpt.settings.Config;
import org.junit.Test;
import org.osgi.service.event.Event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ORPHAN-1 — {@code RateLimitInterceptor}/HTTP clients are never rebuilt on a live config change without a
 * restart. {@code Service.init()} is {@code private synchronized} and guarded by {@code if (!initialized)};
 * {@code handleEvent()} calls {@code init()} unconditionally on every {@code CONFIG_UPDATED}/
 * {@code CONFIG_UPDATED_REQUIRE_REINDEX} event, but since {@code initialized} is only reset to {@code false}
 * in {@code stop()}, every {@code init()} call after the first is a silent no-op - the
 * {@code RateLimitInterceptor} (built once inside {@code init()}) is never rebuilt, so a live
 * {@code rateLimitRequestsPerSecond}/token change has no effect until the module bundle is actually
 * restarted.
 *
 * <p>Constructs a bare {@code Service} via reflection (the same technique {@code ServiceAcceptablePathTest}
 * uses), reflectively marks it already-{@code initialized}, stashes a sentinel {@code customGptClient}
 * reference, then fires a {@code CONFIG_UPDATED} event and asserts the client field is still
 * reference-identical - proving {@code init()} truly no-ops on the second call.
 */
public class ServiceHandleEventNoRebuildTest {

    private static void setInitialized(Service service, boolean value) throws Exception {
        final Field field = Service.class.getDeclaredField("initialized");
        field.setAccessible(true);
        field.set(service, value);
    }

    private static void setCustomGptClient(Service service, OkHttpClient client) throws Exception {
        final Field field = Service.class.getDeclaredField("customGptClient");
        field.setAccessible(true);
        field.set(service, client);
    }

    private static OkHttpClient getCustomGptClient(Service service) throws Exception {
        final Field field = Service.class.getDeclaredField("customGptClient");
        field.setAccessible(true);
        return (OkHttpClient) field.get(service);
    }

    private static Event configUpdatedEvent(String type) {
        final Event event = mock(Event.class);
        when(event.getTopic()).thenReturn(CustomGptConstants.EVENT_TOPIC);
        when(event.getProperty("type")).thenReturn(type);
        return event;
    }

    @Test
    public void handleEvent_configUpdated_afterAlreadyInitialized_doesNotRebuildHttpClient() throws Exception {
        final Service service = Service.class.getDeclaredConstructor().newInstance();
        final Config config = mock(Config.class);
        when(config.isConfigured()).thenReturn(true);
        service.setCustomGptConfig(config);

        setInitialized(service, true);
        final OkHttpClient sentinelClient = new OkHttpClient.Builder().build();
        setCustomGptClient(service, sentinelClient);

        service.handleEvent(configUpdatedEvent(CustomGptConstants.EVENT_TYPE_CONFIG_UPDATED));

        assertThat(getCustomGptClient(service))
                .as("init() must no-op on every call after the first - the HTTP client (and its "
                        + "RateLimitInterceptor) must not be silently rebuilt without a module restart")
                .isSameAs(sentinelClient);
    }

    @Test
    public void handleEvent_configUpdatedRequireReindex_afterAlreadyInitialized_stillDoesNotRebuildHttpClient() throws Exception {
        // Even the "require reindex" event type - which does trigger reIndexUsingJob()/
        // resetScheduleJobASAP() - must not rebuild the already-initialized HTTP client.
        final Service service = Service.class.getDeclaredConstructor().newInstance();
        final Config config = mock(Config.class);
        when(config.isConfigured()).thenReturn(true);
        service.setCustomGptConfig(config);
        // getIndexedSites() (called by reIndexUsingJob()) hits real JCR/OSGi statics if reached; guard by
        // using a scheduler service that will simply fail quietly - what matters here is the client identity.
        service.setSchedulerService(mock(org.jahia.services.scheduler.SchedulerService.class));

        setInitialized(service, true);
        final OkHttpClient sentinelClient = new OkHttpClient.Builder().build();
        setCustomGptClient(service, sentinelClient);

        try {
            service.handleEvent(configUpdatedEvent(CustomGptConstants.EVENT_TYPE_CONFIG_UPDATED_REQUIRE_REINDEX));
        } catch (RuntimeException e) {
            // reIndexUsingJob()/resetScheduleJobASAP() may fail against an un-initialised JCR/OSGi runtime;
            // irrelevant here - init()'s no-op already ran (it is called before reIndexUsingJob()).
        }

        assertThat(getCustomGptClient(service)).isSameAs(sentinelClient);
    }
}
