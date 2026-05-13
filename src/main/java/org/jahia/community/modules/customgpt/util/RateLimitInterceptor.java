package org.jahia.community.modules.customgpt.util;

import java.io.IOException;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OkHttp3 interceptor that enforces a token-bucket rate limit before each request to the
 * CustomGPT.ai API, then retries up to {@value #MAX_RETRIES} times with exponential
 * back-off (honouring the {@code Retry-After} header when present) on HTTP 429 responses.
 *
 * <p>The bucket capacity equals {@code requestsPerSecond}. Tokens are refilled lazily on
 * each call based on elapsed wall-clock time, so no background thread is required.
 * All bucket state is guarded by {@code bucketLock} so the interceptor is safe to share
 * across the concurrent threads used by the bulk-delete executor.
 */
public class RateLimitInterceptor implements Interceptor {

    private static final Logger LOGGER = LoggerFactory.getLogger(RateLimitInterceptor.class);

    private static final int HTTP_TOO_MANY_REQUESTS = 429;
    private static final int MAX_RETRIES = 3;
    private static final long BASE_BACKOFF_MS = 1_000L;
    private static final long MAX_BACKOFF_MS = 30_000L;
    private static final String RETRY_AFTER_HEADER = "Retry-After";

    // Token bucket
    private final int capacity;
    private final long tokenIntervalNanos;
    private long availableTokens;
    private long lastRefillNanos;
    private final Object bucketLock = new Object();

    public RateLimitInterceptor(int requestsPerSecond) {
        if (requestsPerSecond <= 0) {
            throw new IllegalArgumentException("requestsPerSecond must be > 0, got: " + requestsPerSecond);
        }
        this.capacity = requestsPerSecond;
        this.tokenIntervalNanos = 1_000_000_000L / requestsPerSecond;
        this.availableTokens = requestsPerSecond;
        this.lastRefillNanos = System.nanoTime();
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        try {
            acquireToken();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Rate limiter interrupted while waiting for a token", e);
        }

        final Request request = chain.request();
        Response response = chain.proceed(request);

        for (int attempt = 1; attempt <= MAX_RETRIES && response.code() == HTTP_TOO_MANY_REQUESTS; attempt++) {
            final long waitMs = computeBackoffMs(response, attempt);
            response.close();
            LOGGER.warn("HTTP 429 on attempt {}/{} for {} — backing off {}ms",
                    attempt, MAX_RETRIES, request.url(), waitMs);
            try {
                Thread.sleep(waitMs);
                acquireToken();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Rate limit back-off interrupted", e);
            }
            response = chain.proceed(request);
        }

        return response;
    }

    private void acquireToken() throws InterruptedException {
        synchronized (bucketLock) {
            refill();
            if (availableTokens <= 0) {
                final long waitNanos = tokenIntervalNanos - (System.nanoTime() - lastRefillNanos);
                if (waitNanos > 0) {
                    Thread.sleep(waitNanos / 1_000_000L);
                }
                refill();
            }
            availableTokens = Math.max(0L, availableTokens - 1);
        }
    }

    private void refill() {
        final long now = System.nanoTime();
        final long elapsed = now - lastRefillNanos;
        final long newTokens = elapsed / tokenIntervalNanos;
        if (newTokens > 0) {
            availableTokens = Math.min(capacity, availableTokens + newTokens);
            lastRefillNanos += newTokens * tokenIntervalNanos;
        }
    }

    private long computeBackoffMs(Response response, int attempt) {
        final String retryAfter = response.header(RETRY_AFTER_HEADER);
        if (retryAfter != null) {
            try {
                return Long.parseLong(retryAfter.trim()) * 1_000L;
            } catch (NumberFormatException ignored) {
                // header is a date-time string, not seconds — fall through
            }
        }
        // Full-jitter exponential back-off: random value in [0, min(MAX_BACKOFF, BASE * 2^(attempt-1))]
        final long cap = Math.min(MAX_BACKOFF_MS, BASE_BACKOFF_MS << (attempt - 1));
        return (long) (Math.random() * cap);
    }
}
