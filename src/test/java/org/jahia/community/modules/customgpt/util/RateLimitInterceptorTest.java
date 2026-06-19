package org.jahia.community.modules.customgpt.util;

import java.io.IOException;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RateLimitInterceptor}.
 *
 * Timing-sensitive paths (acquireToken waits) are avoided: the test suite asserts on
 * {@code computeBackoffMs} return-value ranges and on chain.proceed call-counts only.
 * The rate-limit bucket is seeded with capacity = 100 rps so the first token is always
 * available instantly.  Retry-After headers with value "0" keep sleep durations to 0 ms,
 * making retry-loop tests near-instant.
 *
 * All {@link Response} objects built in this file carry an empty body (required by OkHttp 4
 * to make the response closeable).
 */
public class RateLimitInterceptorTest {

    // ---- constructor validation ----

    @Test
    public void constructor_rejectsZeroRequestsPerSecond() {
        assertThatThrownBy(() -> new RateLimitInterceptor(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestsPerSecond must be > 0");
    }

    @Test
    public void constructor_rejectsNegativeRequestsPerSecond() {
        assertThatThrownBy(() -> new RateLimitInterceptor(-5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestsPerSecond must be > 0");
    }

    @Test
    public void constructor_acceptsPositiveRequestsPerSecond() {
        // Constructing with any positive rps must not throw and must yield a usable instance.
        assertThatCode(() -> {
            assertThat(new RateLimitInterceptor(1)).isNotNull();
            assertThat(new RateLimitInterceptor(10)).isNotNull();
            assertThat(new RateLimitInterceptor(100)).isNotNull();
        }).doesNotThrowAnyException();
    }

    // ---- computeBackoffMs: numeric Retry-After ----

    @Test
    public void computeBackoffMs_numericRetryAfterIsConvertedToMilliseconds() throws Exception {
        // Arrange
        final RateLimitInterceptor interceptor = new RateLimitInterceptor(100);
        final Response response = buildResponse(429, null, "3");

        // Act — computeBackoffMs is private; reflective access is the only way to test it
        // directly (same-package visibility is insufficient for private methods)
        final long backoffMs = invokeComputeBackoffMs(interceptor, response, 1);

        // Assert: 3 seconds * 1000 = 3000 ms
        assertThat(backoffMs).isEqualTo(3_000L);
    }

    @Test
    public void computeBackoffMs_numericRetryAfterWithLeadingWhitespace() throws Exception {
        final RateLimitInterceptor interceptor = new RateLimitInterceptor(100);
        final Response response = buildResponse(429, null, "  5  ");

        final long backoffMs = invokeComputeBackoffMs(interceptor, response, 1);

        assertThat(backoffMs).isEqualTo(5_000L);
    }

    // ---- computeBackoffMs: date-string Retry-After (falls back to exponential back-off) ----

    // S5976: kept as separate cases — each asserts a distinct per-attempt back-off cap and
    // uses reflection; a JUnit4 Parameterized group cannot be scoped here without a risky
    // Enclosed-runner restructure of this mixed test class.
    @SuppressWarnings("java:S5976")
    @Test
    public void computeBackoffMs_dateStringRetryAfterFallsBackToBoundedExponential_attempt1()
            throws Exception {
        final RateLimitInterceptor interceptor = new RateLimitInterceptor(100);
        // An HTTP-date is not a number — triggers the exponential fallback
        final Response response = buildResponse(429, null, "Thu, 01 Jan 2099 00:00:00 GMT");

        final long backoffMs = invokeComputeBackoffMs(interceptor, response, 1);

        // attempt=1 → cap = min(30000, 1000 << 0) = 1000; result in [0, 1000]
        assertThat(backoffMs).isBetween(0L, 1_000L);
    }

    @Test
    public void computeBackoffMs_dateStringRetryAfterFallsBackToBoundedExponential_attempt2()
            throws Exception {
        final RateLimitInterceptor interceptor = new RateLimitInterceptor(100);
        final Response response = buildResponse(429, null, "not-a-number");

        final long backoffMs = invokeComputeBackoffMs(interceptor, response, 2);

        // attempt=2 → cap = min(30000, 1000 << 1) = 2000; result in [0, 2000]
        assertThat(backoffMs).isBetween(0L, 2_000L);
    }

    @Test
    public void computeBackoffMs_dateStringRetryAfterFallsBackToBoundedExponential_attempt3()
            throws Exception {
        final RateLimitInterceptor interceptor = new RateLimitInterceptor(100);
        final Response response = buildResponse(429, null, "not-a-number");

        final long backoffMs = invokeComputeBackoffMs(interceptor, response, 3);

        // attempt=3 → cap = min(30000, 1000 << 2) = 4000; result in [0, 4000]
        assertThat(backoffMs).isBetween(0L, 4_000L);
    }

    @Test
    public void computeBackoffMs_noRetryAfterHeaderUsesExponentialBackoff() throws Exception {
        final RateLimitInterceptor interceptor = new RateLimitInterceptor(100);
        // Response with no Retry-After header
        final Response response = buildResponse(429, null, null);

        final long backoffMs = invokeComputeBackoffMs(interceptor, response, 1);

        assertThat(backoffMs).isBetween(0L, 1_000L);
    }

    @Test
    public void computeBackoffMs_maxBackoffCapIsRespected() throws Exception {
        // At very high attempt numbers the cap must never exceed MAX_BACKOFF_MS (30 000 ms).
        final RateLimitInterceptor interceptor = new RateLimitInterceptor(100);
        final Response response = buildResponse(429, null, "not-a-number");

        // attempt=20 → 1000 << 19 would overflow but Math.min(30000, ...) caps it
        final long backoffMs = invokeComputeBackoffMs(interceptor, response, 20);

        assertThat(backoffMs).isBetween(0L, 30_000L);
    }

    // ---- 429-retry loop via mocked Chain ----

    @Test
    public void intercept_retriesOn429ThenReturns200() throws Exception {
        // Arrange: 2 × 429 then 1 × 200; Retry-After: 0 keeps back-off sleep to 0 ms
        final RateLimitInterceptor interceptor = new RateLimitInterceptor(100);
        final Interceptor.Chain chain = mock(Interceptor.Chain.class);
        final Request request = dummyRequest();

        when(chain.request()).thenReturn(request);
        when(chain.proceed(request))
                .thenReturn(buildResponse(429, request, "0"))
                .thenReturn(buildResponse(429, request, "0"))
                .thenReturn(buildResponse(200, request, null));

        // Act
        final Response result = interceptor.intercept(chain);

        // Assert: initial call + 2 retries = 3 total
        verify(chain, times(3)).proceed(request);
        assertThat(result.code()).isEqualTo(200);
    }

    @Test
    public void intercept_stopsAfterMaxRetriesAndReturnsLast429() throws Exception {
        // Arrange: all 4 calls (initial + MAX_RETRIES=3) return 429 with Retry-After: 0
        final RateLimitInterceptor interceptor = new RateLimitInterceptor(100);
        final Interceptor.Chain chain = mock(Interceptor.Chain.class);
        final Request request = dummyRequest();

        when(chain.request()).thenReturn(request);
        when(chain.proceed(request))
                .thenReturn(buildResponse(429, request, "0"))
                .thenReturn(buildResponse(429, request, "0"))
                .thenReturn(buildResponse(429, request, "0"))
                .thenReturn(buildResponse(429, request, "0"));

        // Act
        final Response result = interceptor.intercept(chain);

        // Assert: initial + MAX_RETRIES(3) = 4 total calls; last 429 is returned
        verify(chain, times(4)).proceed(request);
        assertThat(result.code()).isEqualTo(429);
    }

    @Test
    public void intercept_returnsImmediatelyOn200WithSingleProceedCall() throws Exception {
        final RateLimitInterceptor interceptor = new RateLimitInterceptor(100);
        final Interceptor.Chain chain = mock(Interceptor.Chain.class);
        final Request request = dummyRequest();

        when(chain.request()).thenReturn(request);
        when(chain.proceed(request)).thenReturn(buildResponse(200, request, null));

        final Response result = interceptor.intercept(chain);

        // Only one proceed call — no retries needed
        verify(chain, times(1)).proceed(request);
        assertThat(result.code()).isEqualTo(200);
    }

    // ---- InterruptedException path ----

    @Test
    public void intercept_setsInterruptFlagWhenInterruptedDuringBackoff() throws Exception {
        // Strategy: pre-interrupt the thread; the retry back-off calls Thread.sleep(0ms from
        // Retry-After:0) which surfaces the interrupt immediately as InterruptedException.
        // The interceptor re-throws it as IOException("Rate limit back-off interrupted") and
        // re-sets the interrupt flag.
        final RateLimitInterceptor interceptor = new RateLimitInterceptor(100);
        final Interceptor.Chain chain = mock(Interceptor.Chain.class);
        final Request request = dummyRequest();

        when(chain.request()).thenReturn(request);
        // Return 429 so the retry loop tries to sleep — that sleep will surface the interrupt.
        when(chain.proceed(request))
                .thenReturn(buildResponse(429, request, "0"));

        // Pre-interrupt this thread so the very first back-off sleep throws immediately
        Thread.currentThread().interrupt();

        boolean ioExceptionThrown = false;
        try {
            interceptor.intercept(chain);
        } catch (IOException e) {
            ioExceptionThrown = true;
            // The interceptor must have restored the interrupt flag before rethrowing
            assertThat(Thread.currentThread().isInterrupted())
                    .as("interrupt flag must be set after IOException from back-off interruption")
                    .isTrue();
            assertThat(e.getMessage())
                    .as("exception message should mention interruption")
                    .containsIgnoringCase("interrupt");
        } finally {
            // Always clear the interrupt flag so subsequent tests are not affected
            Thread.interrupted();
        }

        assertThat(ioExceptionThrown)
                .as("intercept() should have thrown IOException when interrupted during back-off")
                .isTrue();
    }

    // ---- helpers ----

    private static Request dummyRequest() {
        return new Request.Builder()
                .url("https://app.customgpt.ai/api/v1/test")
                .build();
    }

    /**
     * Builds an OkHttp {@link Response} with an empty body so that {@code response.close()}
     * does not throw.  OkHttp 4 requires a non-null body to close; a response without a body
     * (built without {@code body(...)} call) is "not eligible for a body" and throws
     * {@link IllegalStateException} on {@code close()}.
     */
    private static Response buildResponse(int code, Request request, String retryAfter) {
        final Request req = request != null ? request : dummyRequest();
        final String message = code == 200 ? "OK" : "Too Many Requests";
        final Response.Builder builder = new Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(message)
                .body(ResponseBody.create("", MediaType.get("application/json")));
        if (retryAfter != null) {
            builder.header("Retry-After", retryAfter);
        }
        return builder.build();
    }

    /**
     * Reflectively invokes the private {@code computeBackoffMs(Response, int)} method.
     * This is the only use of reflection in this suite; it is required because the method
     * is private and the contract (returned long value) is what we assert on.
     */
    private static long invokeComputeBackoffMs(RateLimitInterceptor interceptor,
            Response response, int attempt) throws Exception {
        final java.lang.reflect.Method m = RateLimitInterceptor.class
                .getDeclaredMethod("computeBackoffMs", Response.class, int.class);
        m.setAccessible(true);
        return (long) m.invoke(interceptor, response, attempt);
    }
}
