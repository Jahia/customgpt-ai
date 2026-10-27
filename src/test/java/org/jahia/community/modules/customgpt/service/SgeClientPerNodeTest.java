package org.jahia.community.modules.customgpt.service;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;

import okhttp3.OkHttpClient;
import org.jahia.api.settings.SettingsBean;
import org.jahia.api.templates.JahiaTemplateManagerService;
import org.jahia.community.modules.customgpt.settings.Config;
import org.jahia.services.templates.TemplatePackageRegistry;
import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the HTTP client the Search Generative Experience lookup uses.
 *
 * <p>Context: the assistant's button appeared on the processing node and on no other. {@code customGptClient}
 * is built inside {@code if (settingsBean.isProcessingServer())} -- correctly, because it exists to index and
 * indexing must run on exactly one node. The SGE lookup was added a release later and reused it, so on every
 * browsing node the field was null and {@code resolveSgeDeployment()} could only read that as "not
 * configured". The lookup is a read performed while rendering a page, and rendering is what browsing nodes
 * do, so it needs a client that exists everywhere.
 *
 * <p>This is invisible to the Cypress suite by construction: that stack is a single node, where
 * {@code isProcessingServer()} is always true. Nothing below needs OSGi, JCR or a network -- {@code Service}
 * is built through its no-arg constructor and its collaborators are injected reflectively, following
 * {@link ServiceShutdownTest}.
 */
public class SgeClientPerNodeTest {

    private Service service;
    private SettingsBean settingsBean;

    @Before
    public void setUp() throws Exception {
        service = Service.class.getDeclaredConstructor().newInstance();

        settingsBean = mock(SettingsBean.class);
        set("settingsBean", settingsBean);

        final Config config = mock(Config.class);
        // RateLimitInterceptor rejects 0, and the processing-server path builds one.
        when(config.getRateLimitRequestsPerSecond()).thenReturn(1);
        set("customGptConfig", config);

        // Only reached on the processing-server path, where registerJcrListeners() runs.
        final TemplatePackageRegistry registry = mock(TemplatePackageRegistry.class);
        final JahiaTemplateManagerService templateManager = mock(JahiaTemplateManagerService.class);
        when(templateManager.getTemplatePackageRegistry()).thenReturn(registry);
        set("jahiaTemplateManagerService", templateManager);

        final IndexService indexService = mock(IndexService.class);
        when(indexService.getIndexedMainResourceNodeTypes()).thenReturn(Collections.singleton("jnt:page"));
        when(indexService.getIndexedSubNodeTypes()).thenReturn(Collections.emptySet());
        set("indexService", indexService);
    }

    private void set(String fieldName, Object value) throws Exception {
        final Field field = Service.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(service, value);
    }

    private OkHttpClient client(String fieldName) throws Exception {
        final Field field = Service.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (OkHttpClient) field.get(service);
    }

    private void init() throws Exception {
        final Method init = Service.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(service);
    }

    /**
     * The defect itself. A browsing node must come out of {@code init()} able to perform the SGE lookup,
     * and must still not hold the indexing clients -- those belong to the processing node alone.
     */
    @Test
    public void browsingNodeGetsAnSgeClientButNoIndexingClients() throws Exception {
        when(settingsBean.isProcessingServer()).thenReturn(false);

        init();

        assertThat(client("sgeClient"))
                .as("a browsing node renders pages, so it must be able to resolve the assistant for itself")
                .isNotNull();
        assertThat(client("customGptClient")).isNull();
        assertThat(client("jahiaClient")).isNull();
    }

    @Test
    public void processingNodeGetsAllThreeClients() throws Exception {
        when(settingsBean.isProcessingServer()).thenReturn(true);

        init();

        assertThat(client("sgeClient")).isNotNull();
        assertThat(client("customGptClient")).isNotNull();
        assertThat(client("jahiaClient")).isNotNull();
    }

    /**
     * The SGE client must keep its own, much shorter budget. Its calls happen inside page rendering on nodes
     * serving the public, where the indexing client's 60-second ceiling would let a slow CustomGPT hold a
     * search page for a minute per call. Pinned so that "simplifying" the two clients back into one, or
     * reusing the indexing constants, fails here rather than in production.
     */
    @Test
    public void theSgeClientIsBoundedFarMoreTightlyThanTheIndexingOne() throws Exception {
        when(settingsBean.isProcessingServer()).thenReturn(true);

        init();

        final OkHttpClient sge = client("sgeClient");
        final OkHttpClient indexing = client("customGptClient");

        assertThat(sge.callTimeoutMillis())
                .as("a page render must not wait on CustomGPT for long")
                .isPositive()
                .isLessThanOrEqualTo(5000)
                .isLessThan(indexing.callTimeoutMillis());
        assertThat(sge.connectTimeoutMillis()).isPositive().isLessThan(indexing.connectTimeoutMillis());
    }

    /** Redirects carry the Bearer token to whatever answers, so this client must refuse them like the others. */
    @Test
    public void theSgeClientDoesNotFollowRedirects() throws Exception {
        when(settingsBean.isProcessingServer()).thenReturn(false);

        init();

        assertThat(client("sgeClient").followRedirects()).isFalse();
        assertThat(client("sgeClient").followSslRedirects()).isFalse();
    }

    /** It is a component-wide resource like the other two, so shutdown must release it as well. */
    @Test
    public void shutdownReleasesTheSgeClient() throws Exception {
        when(settingsBean.isProcessingServer()).thenReturn(false);
        init();
        final OkHttpClient sge = client("sgeClient");

        final Method close = Service.class.getDeclaredMethod("closeHttpClients");
        close.setAccessible(true);
        close.invoke(service);

        assertThat(sge.dispatcher().executorService().isShutdown()).isTrue();
    }
}
