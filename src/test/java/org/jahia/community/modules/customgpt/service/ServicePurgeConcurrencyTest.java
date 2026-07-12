package org.jahia.community.modules.customgpt.service;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jahia.community.modules.customgpt.settings.Config;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit test for F9: the batch/concurrency ceiling in {@link Service#purgeAllPages()}. Concurrency must never
 * exceed {@code Math.max(1, Math.min(batchSize, rateLimitRequestsPerSecond))} (Service.java's
 * {@code threadCount} calculation).
 *
 * <p>Uses a fully mocked {@link OkHttpClient}/{@link Call} (no MockWebServer, no real network — this is a
 * Tier-2 pure-concurrency/timing spec) so the SSRF/HTTPS gate concerns that affect the Tier-3 MockWebServer
 * specs do not apply here: the request URL string is never actually dialled.
 */
public class ServicePurgeConcurrencyTest {

    private static final int TOTAL_PAGES = 12;
    private static final int RATE_LIMIT_RPS = 3;
    private static final int BATCH_SIZE = 20;
    private static final long SIMULATED_DELETE_WORK_MS = 40L;

    @Test
    public void purgeAllPages_neverExceedsMinOfBatchSizeAndRateLimitConcurrency() throws Exception {
        final AtomicInteger inFlight = new AtomicInteger(0);
        final AtomicInteger maxObserved = new AtomicInteger(0);
        final AtomicInteger getCallCount = new AtomicInteger(0);

        final OkHttpClient client = mock(OkHttpClient.class);
        when(client.newCall(any(Request.class))).thenAnswer(invocation -> {
            final Request request = invocation.getArgument(0);
            final Call call = mock(Call.class);
            when(call.execute()).thenAnswer(execInvocation -> {
                if ("GET".equals(request.method())) {
                    final int n = getCallCount.getAndIncrement();
                    final String body = n == 0 ? pagesJson(TOTAL_PAGES) : emptyPagesJson();
                    return jsonResponse(request, body);
                }
                // DELETE: track concurrent in-flight deletes with a synchronized high-water mark, and hold the
                // "connection" open briefly so overlapping calls actually overlap in wall-clock time.
                final int current = inFlight.incrementAndGet();
                synchronized (maxObserved) {
                    maxObserved.set(Math.max(maxObserved.get(), current));
                }
                try {
                    Thread.sleep(SIMULATED_DELETE_WORK_MS);
                } finally {
                    inFlight.decrementAndGet();
                }
                return jsonResponse(request, "{}");
            });
            return call;
        });

        final Config config = mock(Config.class);
        when(config.getCustomGptProjectId()).thenReturn("proj1");
        when(config.getCustomGptToken()).thenReturn("tok");
        when(config.getCustomGptApiBaseUrl()).thenReturn("https://app.customgpt.ai/api/v1");
        when(config.getBulkOperationsBatchSize()).thenReturn(BATCH_SIZE);
        when(config.getRateLimitRequestsPerSecond()).thenReturn(RATE_LIMIT_RPS);

        final Service service = Service.class.getDeclaredConstructor().newInstance();
        service.setCustomGptConfig(config);
        setCustomGptClient(service, client);

        final int deleted = service.purgeAllPages();

        assertThat(deleted).isEqualTo(TOTAL_PAGES);
        assertThat(maxObserved.get())
                .as("observed max concurrent DELETE calls must never exceed min(batchSize=%d, rateLimitRps=%d)",
                        BATCH_SIZE, RATE_LIMIT_RPS)
                .isLessThanOrEqualTo(RATE_LIMIT_RPS)
                .isPositive();
    }

    private static void setCustomGptClient(Service service, OkHttpClient client) throws Exception {
        final Field field = Service.class.getDeclaredField("customGptClient");
        field.setAccessible(true);
        field.set(service, client);
    }

    private static Response jsonResponse(Request request, String body) {
        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(body, MediaType.get("application/json")))
                .build();
    }

    private static String pagesJson(int count) {
        final StringBuilder items = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                items.append(",");
            }
            items.append("{\"id\":").append(1000 + i).append("}");
        }
        return "{\"data\":{\"pages\":{\"data\":[" + items + "]}}}";
    }

    private static String emptyPagesJson() {
        return "{\"data\":{\"pages\":{\"data\":[]}}}";
    }
}
