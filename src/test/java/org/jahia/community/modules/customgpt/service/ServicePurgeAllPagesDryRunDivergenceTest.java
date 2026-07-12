package org.jahia.community.modules.customgpt.service;

import java.lang.reflect.Field;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.jahia.community.modules.customgpt.settings.Config;
import org.jahia.community.modules.customgpt.testutil.HttpsMockWebServerSupport;
import org.junit.After;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D1 — {@code dryRun} does NOT gate {@code purgeAllPages} (danger-zone bulk delete).
 *
 * <p><b>This is a characterization test that currently passes and documents a bug</b> — the single
 * most operator-surprising behavior found in this module (paired with D2's node-removal divergence). An
 * admin who sets {@code dryRun=true} expecting a safe simulation, then clicks "Purge All Pages" believing
 * it is covered by dry-run, will actually trigger real, irreversible {@code DELETE} calls against the live
 * CustomGPT project. {@code Service.purgeAllPages()} contains no {@code isDryRun()} check anywhere in its
 * body, {@code fetchOnePage()}, {@code deleteAllPages()}, or {@code deleteOnePage()}.
 *
 * <p><b>Stage 7 handoff:</b> if/when a {@code dryRun} guard is added to {@code purgeAllPages()} (e.g.
 * {@code if (customGptConfig.isDryRun()) { return 0; }} at the top), THIS TEST'S ASSERTIONS MUST BE
 * INVERTED: the fixed version must assert <b>zero</b> {@code DELETE}/{@code GET .../pages} requests reach
 * the mock server when {@code dryRun=true}, and a {@code 0} (or clearly-labeled dry-run) return value.
 * Leaving the current assertions in place after such a fix would mean this test is silently lying about
 * intended behavior.
 */
public class ServicePurgeAllPagesDryRunDivergenceTest {

    private HttpsMockWebServerSupport.HttpsFixture fixture;

    @After
    public void tearDown() throws Exception {
        if (fixture != null) {
            fixture.shutdown();
        }
    }

    @Test
    public void purgeAllPages_dryRunTrue_stillIssuesLiveDeleteRequests_documentsCurrentDivergence() throws Exception {
        fixture = HttpsMockWebServerSupport.start();

        // Round 1: list 2 pages, delete both, round 2: empty list (loop terminator).
        fixture.server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"data\":{\"pages\":{\"data\":[{\"id\":101},{\"id\":102}]}}}"));
        fixture.server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        fixture.server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        fixture.server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"data\":{\"pages\":{\"data\":[]}}}"));

        final Config config = mock(Config.class);
        when(config.isDryRun()).thenReturn(true);
        when(config.getCustomGptProjectId()).thenReturn("proj1");
        when(config.getCustomGptToken()).thenReturn("secret-token");
        when(config.getCustomGptApiBaseUrl()).thenReturn(fixture.baseUrl());
        when(config.getBulkOperationsBatchSize()).thenReturn(10);
        when(config.getRateLimitRequestsPerSecond()).thenReturn(5);

        final Service service = Service.class.getDeclaredConstructor().newInstance();
        service.setCustomGptConfig(config);
        setCustomGptClient(service, fixture.trustingClientBuilder.build());

        // ---- Act: call purgeAllPages() while isDryRun() is true throughout ----
        final int deleted = service.purgeAllPages();

        // ---- Assert (proves the bug exists today - this assertion currently PASSES) ----
        assertThat(fixture.server.getRequestCount()).isEqualTo(4);

        final RecordedRequest firstGet = fixture.server.takeRequest();
        assertThat(firstGet.getMethod()).isEqualTo("GET");
        assertThat(firstGet.getPath()).contains("/projects/proj1/pages");

        final RecordedRequest delete1 = fixture.server.takeRequest();
        final RecordedRequest delete2 = fixture.server.takeRequest();
        assertThat(java.util.List.of(delete1.getMethod(), delete2.getMethod())).containsOnly("DELETE");
        assertThat(java.util.List.of(delete1.getPath(), delete2.getPath()))
                .containsExactlyInAnyOrder("/projects/proj1/pages/101", "/projects/proj1/pages/102");

        assertThat(deleted).isEqualTo(2);
        // Confirm dryRun was never toggled off - ruling out an implicit reset as an alternative explanation.
        assertThat(config.isDryRun()).isTrue();
    }

    private static void setCustomGptClient(Service service, okhttp3.OkHttpClient client) throws Exception {
        final Field field = Service.class.getDeclaredField("customGptClient");
        field.setAccessible(true);
        field.set(service, client);
    }
}
