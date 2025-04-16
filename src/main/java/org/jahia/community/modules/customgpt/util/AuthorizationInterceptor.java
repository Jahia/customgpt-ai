package org.jahia.community.modules.customgpt.util;

import java.io.IOException;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

public class AuthorizationInterceptor implements Interceptor {

    private final String authorization;

    public AuthorizationInterceptor(String authorization) {
        this.authorization = authorization;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        final Request request = chain.request().newBuilder().addHeader("Authorization", authorization).build();
        final Response response = chain.proceed(request);
        return response;
    }
}
