package org.jahia.community.modules.customgpt.service;

import java.lang.reflect.Field;
import okhttp3.mockwebserver.MockResponse;
import org.jahia.community.modules.customgpt.settings.Config;
import org.jahia.community.modules.customgpt.testutil.HttpsMockWebServerSupport;
import org.junit.After;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F19 — {@code Service.getProjectName()} resolves the CustomGPT project name live on every call; there is
 * no caching layer. Also asserts the graceful-{@code null}-on-failure contract (non-2xx / empty body) never
 * throws.
 */
public class ServiceGetProjectNameTest {

    private HttpsMockWebServerSupport.HttpsFixture fixture;

    @After
    public void tearDown() throws Exception {
        if (fixture != null) {
            fixture.shutdown();
        }
    }

    private static void setCustomGptClient(Service service, okhttp3.OkHttpClient client) throws Exception {
        final Field field = Service.class.getDeclaredField("customGptClient");
        field.setAccessible(true);
        field.set(service, client);
    }

    private static Service newServiceFor(Config config, okhttp3.OkHttpClient client) throws Exception {
        final Service service = Service.class.getDeclaredConstructor().newInstance();
        service.setCustomGptConfig(config);
        setCustomGptClient(service, client);
        return service;
    }

    @Test
    public void getProjectName_resolvesLiveOnEveryCall_neverCached() throws Exception {
        fixture = HttpsMockWebServerSupport.start();
        fixture.server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"data\":{\"project_name\":\"Acme Corp\"}}"));
        fixture.server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"data\":{\"project_name\":\"Acme Corp Renamed\"}}"));

        final Config config = mock(Config.class);
        when(config.getCustomGptProjectId()).thenReturn("proj1");
        when(config.getCustomGptToken()).thenReturn("tok");
        when(config.getCustomGptApiBaseUrl()).thenReturn(fixture.baseUrl());

        final Service service = newServiceFor(config, fixture.trustingClientBuilder.build());

        assertThat(service.getProjectName()).isEqualTo("Acme Corp");
        assertThat(service.getProjectName()).isEqualTo("Acme Corp Renamed");
        assertThat(fixture.server.getRequestCount()).isEqualTo(2);
    }

    @Test
    public void getProjectName_nonSuccessfulResponse_returnsNullNotException() throws Exception {
        fixture = HttpsMockWebServerSupport.start();
        fixture.server.enqueue(new MockResponse().setResponseCode(500));

        final Config config = mock(Config.class);
        when(config.getCustomGptProjectId()).thenReturn("proj1");
        when(config.getCustomGptToken()).thenReturn("tok");
        when(config.getCustomGptApiBaseUrl()).thenReturn(fixture.baseUrl());

        final Service service = newServiceFor(config, fixture.trustingClientBuilder.build());

        assertThat(service.getProjectName()).isNull();
    }

    @Test
    public void getProjectName_emptyBody_returnsNullNotException() throws Exception {
        fixture = HttpsMockWebServerSupport.start();
        fixture.server.enqueue(new MockResponse().setResponseCode(200));

        final Config config = mock(Config.class);
        when(config.getCustomGptProjectId()).thenReturn("proj1");
        when(config.getCustomGptToken()).thenReturn("tok");
        when(config.getCustomGptApiBaseUrl()).thenReturn(fixture.baseUrl());

        final Service service = newServiceFor(config, fixture.trustingClientBuilder.build());

        assertThat(service.getProjectName()).isNull();
    }

    @Test
    public void getProjectName_blankProjectId_returnsNullWithoutAnyHttpCall() throws Exception {
        final Config config = mock(Config.class);
        when(config.getCustomGptProjectId()).thenReturn("");

        final Service service = Service.class.getDeclaredConstructor().newInstance();
        service.setCustomGptConfig(config);

        assertThat(service.getProjectName()).isNull();
    }
}
