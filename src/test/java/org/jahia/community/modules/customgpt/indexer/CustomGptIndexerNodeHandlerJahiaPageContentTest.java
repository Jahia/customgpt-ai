package org.jahia.community.modules.customgpt.indexer;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.jahia.community.modules.customgpt.settings.Config;
import org.jahia.community.modules.customgpt.testutil.HttpsMockWebServerSupport;
import org.jahia.community.modules.customgpt.testutil.LogCapture;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the package-private {@code CustomGptIndexerNodeHandler.getJahiaPageContent(OkHttpClient,
 * String, Config)} — a clean, fully-parameterized private static method, reflection-invoked directly (no
 * Service/OSGi activation needed):
 * <ul>
 *   <li>F14 — Basic auth is gated to HTTPS-only (the {@code isHttpsUrl()} check here is a non-throwing
 *       branch, not a throwing SSRF gate — see the Tier-3 note; both plain HTTP and HTTPS MockWebServer
 *       instances are used deliberately here, one per sub-case).</li>
 *   <li>U12 — a second, independent retry loop (up to {@code CustomGptConstants.MAX_RETRIES}=3, fixed
 *       {@code RETRY_DELAY_MS}=500ms) on any non-successful rendering response.</li>
 * </ul>
 */
public class CustomGptIndexerNodeHandlerJahiaPageContentTest {

    private ListAppender<ILoggingEvent> appender;

    @Before
    public void setUp() {
        appender = LogCapture.attach(CustomGptIndexerNodeHandler.class);
    }

    @After
    public void tearDown() {
        LogCapture.detach(CustomGptIndexerNodeHandler.class, appender);
    }

    private static Response invokeGetJahiaPageContent(OkHttpClient client, String url, Config config) throws Throwable {
        final Method m = CustomGptIndexerNodeHandler.class.getDeclaredMethod(
                "getJahiaPageContent", OkHttpClient.class, String.class, Config.class);
        m.setAccessible(true);
        try {
            return (Response) m.invoke(null, client, url, config);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    // ---- F14: Basic auth gated to HTTPS-only ----

    @Test
    public void getJahiaPageContent_httpUrl_noAuthorizationHeader_warnsAndSkips() throws Throwable {
        final Config config = mock(Config.class);
        when(config.getJahiaUsername()).thenReturn("root");
        when(config.getJahiaPassword()).thenReturn("secret");

        try (MockWebServer plainServer = new MockWebServer()) {
            plainServer.start();
            plainServer.enqueue(new MockResponse().setResponseCode(200).setBody("<html>ok</html>"));

            final OkHttpClient client = new OkHttpClient.Builder().build();
            final String url = "http://localhost:" + plainServer.getPort() + "/page.html";

            try (Response response = invokeGetJahiaPageContent(client, url, config)) {
                assertThat(response.isSuccessful()).isTrue();
            }

            final RecordedRequest recorded = plainServer.takeRequest();
            assertThat(recorded.getHeader("Authorization")).isNull();
            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(m -> m.contains("Skipping Basic authentication"));
        }
    }

    @Test
    public void getJahiaPageContent_httpsUrl_carriesBasicAuthHeader() throws Throwable {
        final Config config = mock(Config.class);
        when(config.getJahiaUsername()).thenReturn("root");
        when(config.getJahiaPassword()).thenReturn("secret");

        final HttpsMockWebServerSupport.HttpsFixture fixture = HttpsMockWebServerSupport.start();
        try {
            fixture.server.enqueue(new MockResponse().setResponseCode(200).setBody("<html>ok</html>"));

            final OkHttpClient client = fixture.trustingClientBuilder.build();
            final String url = fixture.baseUrl() + "/page.html";

            try (Response response = invokeGetJahiaPageContent(client, url, config)) {
                assertThat(response.isSuccessful()).isTrue();
            }

            final RecordedRequest recorded = fixture.server.takeRequest();
            assertThat(recorded.getHeader("Authorization"))
                    .isEqualTo(okhttp3.Credentials.basic("root", "secret", java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            fixture.shutdown();
        }
    }

    @Test
    public void getJahiaPageContent_noCredentialsConfigured_noAuthorizationHeaderEvenOverHttps() throws Throwable {
        final Config config = mock(Config.class);
        when(config.getJahiaUsername()).thenReturn("");
        when(config.getJahiaPassword()).thenReturn("");

        final HttpsMockWebServerSupport.HttpsFixture fixture = HttpsMockWebServerSupport.start();
        try {
            fixture.server.enqueue(new MockResponse().setResponseCode(200).setBody("<html>ok</html>"));
            final OkHttpClient client = fixture.trustingClientBuilder.build();

            try (Response response = invokeGetJahiaPageContent(client, fixture.baseUrl() + "/page.html", config)) {
                assertThat(response.isSuccessful()).isTrue();
            }

            assertThat(fixture.server.takeRequest().getHeader("Authorization")).isNull();
        } finally {
            fixture.shutdown();
        }
    }

    // ---- U12: independent retry loop on non-successful responses ----

    @Test
    public void getJahiaPageContent_threeConsecutive500s_exactlyThreeAttempts_returnsLastUnsuccessfulResponse() throws Throwable {
        final Config config = mock(Config.class);
        when(config.getJahiaUsername()).thenReturn("");
        when(config.getJahiaPassword()).thenReturn("");

        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(500));
            server.enqueue(new MockResponse().setResponseCode(500));
            server.enqueue(new MockResponse().setResponseCode(500));

            final OkHttpClient client = new OkHttpClient.Builder().build();
            final String url = "http://localhost:" + server.getPort() + "/page.html";

            try (Response response = invokeGetJahiaPageContent(client, url, config)) {
                assertThat(response.isSuccessful()).isFalse();
                assertThat(response.code()).isEqualTo(500);
            }

            assertThat(server.getRequestCount()).isEqualTo(3);
        }
    }

    @Test
    public void getJahiaPageContent_succeedsOnThirdAttempt_doesNotExhaustRetries() throws Throwable {
        final Config config = mock(Config.class);
        when(config.getJahiaUsername()).thenReturn("");
        when(config.getJahiaPassword()).thenReturn("");

        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(500));
            server.enqueue(new MockResponse().setResponseCode(500));
            server.enqueue(new MockResponse().setResponseCode(200).setBody("<html>ok</html>"));

            final OkHttpClient client = new OkHttpClient.Builder().build();
            final String url = "http://localhost:" + server.getPort() + "/page.html";

            try (Response response = invokeGetJahiaPageContent(client, url, config)) {
                assertThat(response.isSuccessful()).isTrue();
            }

            assertThat(server.getRequestCount()).isEqualTo(3);
        }
    }
}
