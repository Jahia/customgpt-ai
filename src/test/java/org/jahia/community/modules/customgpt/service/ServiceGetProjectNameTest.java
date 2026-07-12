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

    /**
     * Fixed in Stage 7 (formerly a characterization test documenting a bug): {@code getProjectName()}'s
     * {@code response.body() == null} guard is effectively unreachable in practice - OkHttp always returns a
     * non-null (possibly empty) {@link okhttp3.ResponseBody} for a completed HTTP response. A genuinely empty
     * body used to reach {@code new JSONObject(response.body().string())} and throw
     * {@link org.json.JSONException}, uncaught by {@code getProjectName()}'s {@code catch (IOException e)}
     * (JSONException is a {@link RuntimeException}, not an IOException) - a real, if minor, gap in the
     * method's documented "returns null, never throws" contract for a plausible failure mode (a
     * gateway/proxy returning 200 with no content).
     *
     * <p>{@code getProjectName()} now explicitly checks for an empty response body string (in addition to
     * the pre-existing, effectively-dead {@code response.body() == null} check) and wraps the
     * {@code JSONObject} parse in a try/catch for {@link org.json.JSONException}, so both a genuinely empty
     * body and any other malformed/non-JSON body gracefully return {@code null} instead of throwing.
     */
    @Test
    public void getProjectName_genuinelyEmptyBody_returnsNullNotException() throws Exception {
        fixture = HttpsMockWebServerSupport.start();
        fixture.server.enqueue(new MockResponse().setResponseCode(200));

        final Config config = mock(Config.class);
        when(config.getCustomGptProjectId()).thenReturn("proj1");
        when(config.getCustomGptToken()).thenReturn("tok");
        when(config.getCustomGptApiBaseUrl()).thenReturn(fixture.baseUrl());

        final Service service = newServiceFor(config, fixture.trustingClientBuilder.build());

        assertThat(service.getProjectName()).isNull();
    }

    /**
     * Companion to the empty-body fix above: a non-empty but syntactically invalid JSON body (a malformed
     * response, distinct from the "well-formed JSON without a usable 'data' field" case already covered by
     * {@code getProjectName_responseBodyPresentButNotAnObject_returnsNullNotException()}) must also return
     * {@code null} rather than propagate {@link org.json.JSONException}.
     */
    @Test
    public void getProjectName_malformedJsonBody_returnsNullNotException() throws Exception {
        fixture = HttpsMockWebServerSupport.start();
        fixture.server.enqueue(new MockResponse().setResponseCode(200).setBody("{not valid json"));

        final Config config = mock(Config.class);
        when(config.getCustomGptProjectId()).thenReturn("proj1");
        when(config.getCustomGptToken()).thenReturn("tok");
        when(config.getCustomGptApiBaseUrl()).thenReturn(fixture.baseUrl());

        final Service service = newServiceFor(config, fixture.trustingClientBuilder.build());

        assertThat(service.getProjectName()).isNull();
    }

    @Test
    public void getProjectName_responseBodyPresentButNotAnObject_returnsNullNotException() throws Exception {
        fixture = HttpsMockWebServerSupport.start();
        // A syntactically valid JSON value that is not an object with a "data" field: optJSONObject("data")
        // returns null, and getProjectName() gracefully returns null rather than throwing.
        fixture.server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

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
