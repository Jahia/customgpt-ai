package org.jahia.community.modules.customgpt.service;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.jahia.community.modules.customgpt.settings.Config;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * U10 — {@code jahiaClient}'s {@link okhttp3.CookieJar} never persists response cookies.
 * {@code saveFromResponse()} is a no-op (comment: "session auth is handled by the Bearer authenticator" -
 * more precisely, by the Basic-auth header, see F14), and {@code loadForRequest()} injects only the one
 * statically-configured {@code jahia.serverCookie.*} cookie.
 *
 * <p>Uses the package-visible {@code Service.buildJahiaClient(Config)} factory method (extracted from
 * {@code init()} purely for testability).
 */
public class ServiceJahiaCookieJarTest {

    @Test
    public void loadForRequest_neverPersistsResponseSetCookie_onlyInjectsConfiguredCookie() throws Exception {
        final Config config = mock(Config.class);
        when(config.getJahiaServerCookieName()).thenReturn("sess");
        when(config.getJahiaServerCookieValue()).thenReturn("abc123");
        when(config.getJahiaServerCookieDomain()).thenReturn("localhost");

        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(200)
                    .addHeader("Set-Cookie", "tracking=xyz; Path=/")
                    .setBody("first"));
            server.enqueue(new MockResponse().setResponseCode(200).setBody("second"));

            final OkHttpClient client = Service.buildJahiaClient(config);
            final Request request = new Request.Builder().url(server.url("/page.html")).get().build();

            // Request 1: receives the Set-Cookie.
            try (Response response = client.newCall(request).execute()) {
                assertThat(response.isSuccessful()).isTrue();
            }
            final RecordedRequest firstRecorded = server.takeRequest();
            // The very first request carries only the configured cookie too (no prior response yet).
            assertThat(firstRecorded.getHeader("Cookie")).isEqualTo("sess=abc123");

            // Request 2: must carry ONLY the statically-configured cookie, never the tracking cookie the
            // server tried to set - proving saveFromResponse()'s no-op truly discards response cookies.
            try (Response response = client.newCall(request).execute()) {
                assertThat(response.isSuccessful()).isTrue();
            }
            final RecordedRequest secondRecorded = server.takeRequest();
            assertThat(secondRecorded.getHeader("Cookie"))
                    .isEqualTo("sess=abc123")
                    .doesNotContain("tracking");
        }
    }

    @Test
    public void loadForRequest_noCookieConfigured_injectsNoCookieHeader() throws Exception {
        final Config config = mock(Config.class);
        when(config.getJahiaServerCookieName()).thenReturn("");
        when(config.getJahiaServerCookieValue()).thenReturn("");

        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

            final OkHttpClient client = Service.buildJahiaClient(config);
            final Request request = new Request.Builder().url(server.url("/page.html")).get().build();

            try (Response response = client.newCall(request).execute()) {
                assertThat(response.isSuccessful()).isTrue();
            }

            assertThat(server.takeRequest().getHeader("Cookie")).isNull();
        }
    }
}
