package org.jahia.community.modules.customgpt.service;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
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
 * F6 — {@code purgeAllPages()}'s pagination re-fetch contract. Directly proves the AGENTS.md-documented
 * pagination-drift pitfall does not regress: {@code fetchOnePage()} always re-queries the same first-page
 * URL (never {@code next_page_url}), so items that would have been "page 2" before any deletion (and would
 * be skipped if the loop instead followed an offset-based {@code next_page_url}) are still picked up on the
 * next round once page 1 empties out.
 */
public class ServicePurgeAllPagesPaginationTest {

    private HttpsMockWebServerSupport.HttpsFixture fixture;

    @After
    public void tearDown() throws Exception {
        if (fixture != null) {
            fixture.shutdown();
        }
    }

    @Test
    public void purgeAllPages_reQueriesSameFirstPageUrl_pickingUpDriftedItems_allThreeDeleted() throws Exception {
        fixture = HttpsMockWebServerSupport.start();

        // Round 1: 2 page ids (201, 202) - simulates what would have been "page 1" of a 3-item paginated
        // listing before any deletion.
        fixture.server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"data\":{\"pages\":{\"data\":[{\"id\":201},{\"id\":202}]}}}"));
        fixture.server.enqueue(new MockResponse().setResponseCode(200)); // DELETE 201 or 202
        fixture.server.enqueue(new MockResponse().setResponseCode(200)); // DELETE 202 or 201
        // Round 2: re-querying the SAME first-page URL now surfaces the item that was on "page 2" before
        // round 1's deletes shifted it into page 1 - this is the drift the pagination contract must handle.
        fixture.server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"data\":{\"pages\":{\"data\":[{\"id\":203}]}}}"));
        fixture.server.enqueue(new MockResponse().setResponseCode(200)); // DELETE 203
        // Round 3: empty - loop terminator.
        fixture.server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"data\":{\"pages\":{\"data\":[]}}}"));

        final Config config = mock(Config.class);
        when(config.getCustomGptProjectId()).thenReturn("proj1");
        when(config.getCustomGptToken()).thenReturn("tok");
        when(config.getCustomGptApiBaseUrl()).thenReturn(fixture.baseUrl());
        when(config.getBulkOperationsBatchSize()).thenReturn(10);
        when(config.getRateLimitRequestsPerSecond()).thenReturn(5);

        final Service service = Service.class.getDeclaredConstructor().newInstance();
        service.setCustomGptConfig(config);
        setCustomGptClient(service, fixture.trustingClientBuilder.build());

        final int deleted = service.purgeAllPages();

        assertThat(deleted).isEqualTo(3);

        final List<RecordedRequest> requests = new ArrayList<>();
        for (int i = 0; i < fixture.server.getRequestCount(); i++) {
            requests.add(fixture.server.takeRequest());
        }
        assertThat(requests).hasSize(6);

        final List<RecordedRequest> getRequests = requests.stream()
                .filter(r -> "GET".equals(r.getMethod())).collect(java.util.stream.Collectors.toList());
        final List<RecordedRequest> deleteRequests = requests.stream()
                .filter(r -> "DELETE".equals(r.getMethod())).collect(java.util.stream.Collectors.toList());

        // Exactly 3 GET requests, every one to the same first-page path (never a next_page_url variant).
        assertThat(getRequests).hasSize(3);
        assertThat(getRequests).extracting(RecordedRequest::getPath).containsOnly("/projects/proj1/pages");

        // All 3 page ids received a DELETE - none skipped by the drift.
        assertThat(deleteRequests).hasSize(3);
        assertThat(deleteRequests).extracting(RecordedRequest::getPath).containsExactlyInAnyOrder(
                "/projects/proj1/pages/201", "/projects/proj1/pages/202", "/projects/proj1/pages/203");
    }

    private static void setCustomGptClient(Service service, okhttp3.OkHttpClient client) throws Exception {
        final Field field = Service.class.getDeclaredField("customGptClient");
        field.setAccessible(true);
        field.set(service, client);
    }
}
