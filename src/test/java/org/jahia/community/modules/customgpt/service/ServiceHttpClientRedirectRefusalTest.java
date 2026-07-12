package org.jahia.community.modules.customgpt.service;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.jahia.community.modules.customgpt.settings.Config;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F15 + U14 — redirect-following is disabled on <b>both</b> OkHttp clients the module owns. Uses the
 * package-visible {@code Service.buildCustomGptClient(Config)} / {@code Service.buildJahiaClient(Config)}
 * factory methods (extracted from {@code init()} purely for testability - see the accompanying refactor
 * commit) so the exact same client configuration production uses is exercised directly here, without
 * fighting {@code Service}'s OSGi/JCR-gated activation.
 *
 * <p><b>Scope note:</b> in production the CustomGPT Bearer token is attached reactively by the client's
 * {@code Authenticator} (only after a 401 challenge - see {@code Service.buildCustomGptClient}), and the
 * Jahia Basic-auth header is attached by {@code CustomGptIndexerNodeHandler.getJahiaPageContent()} (see
 * F14), not by the client itself. Both of those attachment mechanisms are already covered by their own
 * tests. Here, a credential header is attached directly on the outgoing request (simulating "a request that
 * already carries the credential") purely to make the redirect-refusal assertion self-contained; this test
 * proves the built {@code OkHttpClient} instances refuse to follow a redirect and never forward that header
 * to the redirect target - it does not re-prove how the header gets attached in the first place.
 */
public class ServiceHttpClientRedirectRefusalTest {

    @Test
    public void customGptClient_doesNotFollowRedirect_bearerTokenNeverReachesRedirectTarget() throws Exception {
        final Config config = mock(Config.class);
        when(config.getCustomGptToken()).thenReturn("secret-bearer-token");
        when(config.getRateLimitRequestsPerSecond()).thenReturn(10);

        try (MockWebServer serverA = new MockWebServer(); MockWebServer serverB = new MockWebServer()) {
            serverA.start();
            serverB.start();
            serverB.enqueue(new MockResponse().setResponseCode(200).setBody("should never be requested"));
            final String serverBUrl = serverB.url("/").toString();
            serverA.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", serverBUrl));

            final OkHttpClient client = Service.buildCustomGptClient(config);
            final Request request = new Request.Builder()
                    .url(serverA.url("/projects/proj1/pages"))
                    .get()
                    .addHeader("Authorization", "Bearer secret-bearer-token")
                    .build();

            try (Response response = client.newCall(request).execute()) {
                assertThat(response.code()).isEqualTo(302);
                assertThat(response.priorResponse()).isNull();
            }

            assertThat(serverB.getRequestCount()).isZero();
        }
    }

    @Test
    public void jahiaClient_doesNotFollowRedirect_credentialNeverReachesRedirectTarget() throws Exception {
        final Config config = mock(Config.class);
        when(config.getJahiaServerCookieName()).thenReturn("sess");
        when(config.getJahiaServerCookieValue()).thenReturn("abc123");
        when(config.getJahiaServerCookieDomain()).thenReturn("localhost");

        try (MockWebServer serverA = new MockWebServer(); MockWebServer serverB = new MockWebServer()) {
            serverA.start();
            serverB.start();
            serverB.enqueue(new MockResponse().setResponseCode(200).setBody("should never be requested"));
            final String serverBUrl = serverB.url("/").toString();
            serverA.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", serverBUrl));

            final OkHttpClient client = Service.buildJahiaClient(config);
            final Request request = new Request.Builder()
                    .url(serverA.url("/page.html"))
                    .get()
                    .addHeader("Authorization", okhttp3.Credentials.basic("root", "secret"))
                    .build();

            try (Response response = client.newCall(request).execute()) {
                assertThat(response.code()).isEqualTo(302);
                assertThat(response.priorResponse()).isNull();
            }

            assertThat(serverB.getRequestCount()).isZero();
        }
    }
}
