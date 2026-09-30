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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for corroborating a metadata read before believing a missing value.
 *
 * <p>The {@code data.id} echo check is necessary but NOT sufficient. This API was observed returning
 * {@code {"status":"success","data":{"id":90955650,...,"url":null}}} for a page confirmed by a direct call to
 * have a real URL: correct status, correct echoed id, dropped payload. The envelope can be right while the value
 * is gone.
 *
 * <p>That defeats a single read in both directions. In the examine phase a dropped read manufactures a rewrite
 * target for a page that was already correct - which is how a 6-worker verification pass produced 2558 false
 * nulls. In the write verification it makes a successful write look like a failure and triggers a pointless
 * rewrite.
 *
 * <p>The degradation drops values rather than inventing them, so corroboration is asymmetric: a value seen in
 * ANY read is real, and only a value absent from every read is believed absent.
 */
public class CorroboratedReadTest {

    private static final String PAGE_ID = "85803619";
    private static final String URL = "https://academy.jahia.com/kb/deploy-on-site";

    private final List<String> reads = new ArrayList<>();

    /** A client whose successive GETs answer the given bodies, the last repeating once exhausted. */
    private OkHttpClient client(String... bodies) throws IOException {
        final OkHttpClient client = mock(OkHttpClient.class);
        final int[] i = {0};
        when(client.newCall(any())).thenAnswer(invocation -> {
            final Request request = invocation.getArgument(0);
            final Call call = mock(Call.class);
            final String body = bodies[Math.min(i[0]++, bodies.length - 1)];
            reads.add(body);
            when(call.execute()).thenReturn(new Response.Builder()
                    .request(request).protocol(Protocol.HTTP_1_1).code(200).message("ok")
                    .body(ResponseBody.create(body, null)).build());
            return call;
        });
        return client;
    }

    private static String payload(String id, String url) {
        return "{\"status\":\"success\",\"data\":{\"id\":" + id + ",\"title\":\"t\",\"description\":null,"
                + "\"image\":null,\"url\":" + (url == null ? "null" : "\"" + url + "\"") + "}}";
    }

    private String read(OkHttpClient client) throws IOException {
        return CustomGptIndexerNodeHandler.readStoredUrl(client, "1", PAGE_ID, "https://api.example");
    }

    @Test
    public void aValuePresentOnTheFirstReadIsReturnedWithoutReReading() throws Exception {
        final OkHttpClient client = client(payload(PAGE_ID, URL));

        assertThat(read(client)).isEqualTo(URL);
        assertThat(reads).hasSize(1);
    }

    @Test
    public void aDroppedValueIsRecoveredByReReading() throws Exception {
        // The observed shape: success, correct echoed id, url null - for a page that has one.
        final OkHttpClient client = client(payload(PAGE_ID, null), payload(PAGE_ID, URL));

        assertThat(read(client)).isEqualTo(URL);
        assertThat(reads).hasSizeGreaterThan(1);
    }

    @Test
    public void aValueAbsentFromEveryReadIsBelievedAbsent() throws Exception {
        // The 44 genuinely null pages must still be detected, or the repair has nothing to fix.
        final OkHttpClient client = client(payload(PAGE_ID, null));

        assertThat(read(client)).isNull();
        assertThat(reads).hasSizeGreaterThan(1);
    }

    @Test
    public void aResponseNamingADifferentPageIsRejectedOutright() throws Exception {
        // Corroboration must not paper over this: another page's metadata is not evidence about this one.
        final OkHttpClient client = client(payload("999", URL));

        assertThatThrownBy(() -> read(client))
                .isInstanceOf(IOException.class)
                .hasMessageContaining(PAGE_ID);
    }
}
