package org.jahia.community.modules.customgpt.service;

import java.lang.reflect.Field;
import org.jahia.community.modules.customgpt.settings.Config;
import org.jahia.community.modules.customgpt.testutil.HttpsMockWebServerSupport;
import org.junit.After;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D1 — {@code dryRun} now correctly gates {@code purgeAllPages} (danger-zone bulk delete).
 *
 * <p><b>Formerly a characterization test documenting a bug</b> (see the Stage 6/7 execution reports for the
 * original {@code purgeAllPages_dryRunTrue_stillIssuesLiveDeleteRequests_documentsCurrentDivergence()} test
 * this class used to contain): an admin who sets {@code dryRun=true} expecting a safe simulation, then clicks
 * "Purge All Pages" believing it is covered by dry-run, used to trigger real, irreversible {@code DELETE}
 * calls against the live CustomGPT project, because {@code Service.purgeAllPages()} contained no
 * {@code isDryRun()} check anywhere in its body, {@code fetchOnePage()}, {@code deleteAllPages()}, or
 * {@code deleteOnePage()}.
 *
 * <p><b>Fixed in Stage 7:</b> {@code purgeAllPages()} now short-circuits at the very top when
 * {@code customGptConfig.isDryRun()} is {@code true} — it issues <em>zero</em> HTTP requests (not even the
 * enumerating {@code GET .../pages} call) and returns {@code 0}, unambiguously signalling "dry-run, nothing
 * was touched" rather than a coincidental "zero pages existed" result.
 *
 * <p>The dry-run-specific nature of the guard (i.e. that {@code dryRun=false} purges are completely
 * unaffected) is independently proven by {@link ServicePurgeAllPagesPaginationTest} and
 * {@link ServicePurgeConcurrencyTest}, both of which exercise {@code purgeAllPages()} with an explicit
 * {@code isDryRun()==false} config and assert real GET/DELETE traffic and non-zero deletion counts.
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
    public void purgeAllPages_dryRunTrue_skipsAllRequestsAndReturnsZero() throws Exception {
        fixture = HttpsMockWebServerSupport.start();

        // Deliberately zero responses enqueued: if the dry-run guard did not fire before any HTTP call, the
        // very first GET .../pages request would fail with a connection/response error rather than silently
        // succeeding, so this test would fail loudly instead of passing for the wrong reason.

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

        // ---- Assert: the dry-run guard suppressed every GET/DELETE request and clearly signals "0, dry-run" ----
        assertThat(deleted).isEqualTo(0);
        assertThat(fixture.server.getRequestCount())
                .as("no GET .../pages or DELETE .../pages/{id} request should reach the mock server when "
                        + "isDryRun()==true")
                .isEqualTo(0);
        // Confirm dryRun was never toggled off - ruling out an implicit reset as an alternative explanation.
        assertThat(config.isDryRun()).isTrue();
    }

    private static void setCustomGptClient(Service service, okhttp3.OkHttpClient client) throws Exception {
        final Field field = Service.class.getDeclaredField("customGptClient");
        field.setAccessible(true);
        field.set(service, client);
    }
}
