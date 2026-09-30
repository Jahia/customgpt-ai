package org.jahia.community.modules.customgpt.indexer;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression tests for read-back verification of the page metadata write.
 *
 * <p>{@code updatePageMetadataChecked} trusted {@code isSuccessful()} and read nothing back. That is not enough
 * against this API: under sustained bulk load it answers {@code status: success} with an empty body while
 * reporting zero errors - a dropped write wearing a 2xx. 47 of 1920 pages in the production corpus carry no URL
 * as a result, which means 47 citations users cannot click, and because the PUT "succeeded" no log line anywhere
 * records it.
 *
 * <p>The verification echoes {@code data.id} as well as comparing {@code data.url}. A response that does not name
 * the page that was asked for is exactly the signature measured at high concurrency.
 */
public class CustomGptIndexerNodeHandlerMetadataTest {

    private static final String PROJECT = "68402";
    private static final String PAGE_ID = "85803619";
    private static final String TITLE = "Spring Bean modifications";
    private static final String URL = "https://academy.example/kb/spring-beans";
    private static final String BASE = "https://app.customgpt.ai/api/v1";

    private final List<String> calls = new ArrayList<>();

    private static Response respond(Request request, int code, String body) {
        final Response.Builder builder = new Response.Builder()
                .request(request).protocol(Protocol.HTTP_1_1).code(code).message("status " + code);
        if (body != null) {
            builder.body(ResponseBody.create(body, null));
        }
        return builder.build();
    }

    /**
     * A client whose PUT answers {@code putCode} and whose successive GETs answer the given bodies, the last one
     * repeating once exhausted.
     */
    private OkHttpClient client(int putCode, String... getBodies) throws IOException {
        final OkHttpClient client = mock(OkHttpClient.class);
        final int[] getIndex = {0};
        when(client.newCall(any())).thenAnswer(invocation -> {
            final Request request = invocation.getArgument(0);
            final Call call = mock(Call.class);
            calls.add(request.method());
            if ("PUT".equals(request.method())) {
                when(call.execute()).thenReturn(respond(request, putCode, "{\"status\":\"success\"}"));
            } else {
                final int i = Math.min(getIndex[0]++, getBodies.length - 1);
                when(call.execute()).thenReturn(respond(request, 200, getBodies[i]));
            }
            return call;
        });
        return client;
    }

    private void update(OkHttpClient client) throws IOException {
        CustomGptIndexerNodeHandler.updatePageMetadataChecked(client, PROJECT, PAGE_ID, TITLE, URL, BASE);
    }

    private static String stored(String url) {
        return "{\"status\":\"success\",\"data\":{\"id\":" + PAGE_ID + ",\"title\":\"t\",\"description\":null,"
                + "\"image\":null,\"url\":" + (url == null ? "null" : "\"" + url + "\"") + "}}";
    }

    @Test
    public void aWriteThatTookIsAcceptedAfterOneAttempt() throws Exception {
        final OkHttpClient client = client(200, stored(URL));
        assertThatCode(() -> update(client)).doesNotThrowAnyException();
        assertThat(calls).containsExactly("PUT", "GET");
    }

    @Test
    public void aWriteThatSilentlyDidNotTakeIsRaised() throws Exception {
        // The production shape: a 2xx on the PUT, and the URL still null when read back.
        final OkHttpClient client = client(200, stored(null));
        assertThatThrownBy(() -> update(client))
                .isInstanceOf(IOException.class)
                .hasMessageContaining(PAGE_ID);
        // It must actually retry rather than give up on the first disagreement.
        assertThat(calls).hasSizeGreaterThan(2);
    }

    @Test
    public void aDroppedReadDoesNotTriggerAPointlessSecondWrite() throws Exception {
        // The write took; it was the READ that lost the value. Corroborating the read recognises that as a
        // success, rather than answering it with another write against an API that drops writes under load.
        final OkHttpClient client = client(200, stored(null), stored(URL));
        assertThatCode(() -> update(client)).doesNotThrowAnyException();
        assertThat(calls).containsExactly("PUT", "GET", "GET");
    }

    @Test
    public void aResponseNamingADifferentPageIsRaised() throws Exception {
        // The signature measured at 6 concurrent workers: a success status that does not echo the id asked for.
        final String wrongPage = "{\"status\":\"success\",\"data\":{\"id\":999,\"url\":\"" + URL + "\"}}";
        final OkHttpClient client = client(200, wrongPage);
        assertThatThrownBy(() -> update(client))
                .isInstanceOf(IOException.class)
                .hasMessageContaining(PAGE_ID);
    }

    @Test
    public void anEmptyResponseBodyIsRaised() throws Exception {
        final OkHttpClient client = client(200, "{\"status\":\"success\"}");
        assertThatThrownBy(() -> update(client)).isInstanceOf(IOException.class);
    }

    @Test
    public void aFailedPutStillReportsItsStatusAndBody() throws Exception {
        // Pre-existing behaviour that must survive: a non-2xx PUT names the status, and does not read back.
        final OkHttpClient client = client(422, stored(URL));
        assertThatThrownBy(() -> update(client))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("422");
        assertThat(calls).containsExactly("PUT");
    }
}
