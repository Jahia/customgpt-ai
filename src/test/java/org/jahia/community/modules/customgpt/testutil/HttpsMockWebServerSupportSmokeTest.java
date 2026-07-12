package org.jahia.community.modules.customgpt.testutil;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import org.jahia.community.modules.customgpt.util.SecurityUtils;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test for {@link HttpsMockWebServerSupport}: confirms the fixture's base URL passes
 * {@code SecurityUtils.resolveHttpsBaseUrl()} (the SSRF/HTTPS gate every Tier-3 spec must clear) and that a
 * real request round-trips successfully over the self-signed HTTPS connection.
 */
public class HttpsMockWebServerSupportSmokeTest {

    @Test
    public void baseUrlPassesHttpsGate_andRequestRoundTrips() throws Exception {
        final HttpsMockWebServerSupport.HttpsFixture fixture = HttpsMockWebServerSupport.start();
        try {
            // Must not throw IllegalStateException (would mean the SSRF/HTTPS gate rejected it).
            final String resolved = SecurityUtils.resolveHttpsBaseUrl(fixture.baseUrl(), "https://default.invalid");
            assertThat(resolved).isEqualTo(fixture.baseUrl());
            assertThat(SecurityUtils.isHttpsUrl(fixture.baseUrl())).isTrue();

            fixture.server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
            final OkHttpClient client = fixture.trustingClientBuilder.build();
            final Request request = new Request.Builder().url(fixture.baseUrl() + "/ping").get().build();
            try (Response response = client.newCall(request).execute()) {
                assertThat(response.isSuccessful()).isTrue();
                assertThat(response.body().string()).isEqualTo("ok");
            }
        } finally {
            fixture.shutdown();
        }
    }
}
