package org.jahia.community.modules.customgpt.util;

import java.io.IOException;
import okhttp3.Interceptor;
import okhttp3.Response;
import org.jahia.community.modules.customgpt.service.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RateLimitInterceptor implements Interceptor {

    private static final Logger LOGGER = LoggerFactory.getLogger(Service.class);
    private static final int HTTP_TOO_MANY_REQUESTS = 429;
    private static final int MAX_WAIT_MS = 1000;
    private static final int MIN_WAIT_MS = 500;
    private static final int SLEEP_BEF_RETRY_MS = 1000;

    public RateLimitInterceptor() {
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Response response = chain.proceed(chain.request());
        try {

            if (response.isSuccessful()) {
                Thread.sleep((long) ((Math.random() * (MAX_WAIT_MS - MIN_WAIT_MS)) + MIN_WAIT_MS));
            } else if (response.code() == HTTP_TOO_MANY_REQUESTS) {
                LOGGER.warn(String.format("%s: %s, waiting for %s", HTTP_TOO_MANY_REQUESTS, response.message(), SLEEP_BEF_RETRY_MS));
                Thread.sleep(SLEEP_BEF_RETRY_MS);
                response = chain.proceed(chain.request());
            }
        } catch (InterruptedException ex) {
            LOGGER.error("Impossible to pause the thread", ex);
            Thread.currentThread().interrupt();
        }
        return response;
    }
}
