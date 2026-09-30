package org.jahia.community.modules.customgpt.indexer;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests that reading a page's metadata distinguishes "this page no longer exists" from a real failure.
 *
 * <p>This is not hypothetical bookkeeping. ~1138 pages were removed from the production project directly through
 * the API - duplicates, obsolete trees, and a foreign-site page - without Jahia being involved, so that many
 * mapping nodes now hold a {@code customGptPageId} pointing at a page that is gone. Without this distinction a
 * URL repair run would report every one of them as a failure, burying the handful of genuinely actionable ones.
 *
 * <p>A dangling mapping node is cosmetic and self-healing: the next publication re-indexes the node, and since
 * the delete now treats an absent page as gone, it no longer orphans a replacement.
 */
public class CustomGptIndexerNodeHandlerFetchTest {

    private static final String PAGE_ID = "85803619";

    private static OkHttpClient clientReturning(int status, String body) throws IOException {
        final OkHttpClient client = mock(OkHttpClient.class);
        final Call call = mock(Call.class);
        when(client.newCall(any())).thenAnswer(invocation -> {
            final Request request = invocation.getArgument(0);
            final Response.Builder builder = new Response.Builder()
                    .request(request).protocol(Protocol.HTTP_1_1).code(status).message("status " + status);
            if (body != null) {
                builder.body(ResponseBody.create(body, null));
            }
            when(call.execute()).thenReturn(builder.build());
            return call;
        });
        return client;
    }

    private static void fetch(OkHttpClient client) throws IOException {
        CustomGptIndexerNodeHandler.fetchPageMetadata(client, "1", PAGE_ID, "https://api.example");
    }

    @Test
    public void aDeletedPageIsReportedAsGoneRatherThanAsAFailure() throws Exception {
        // Measured behaviour: this API answers 403 for an id it does not know, not 404.
        assertThatThrownBy(() -> fetch(clientReturning(403, "{\"message\":\"This action is unauthorized.\"}")))
                .isInstanceOf(CustomGptIndexerNodeHandler.PageGoneException.class)
                .hasMessageContaining(PAGE_ID);
    }

    @Test
    public void aNotFoundIsAlsoTreatedAsGone() throws Exception {
        assertThatThrownBy(() -> fetch(clientReturning(404, "{}")))
                .isInstanceOf(CustomGptIndexerNodeHandler.PageGoneException.class);
    }

    @Test
    public void aServerErrorStaysAnOrdinaryFailure() throws Exception {
        assertThatThrownBy(() -> fetch(clientReturning(500, "{}")))
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(CustomGptIndexerNodeHandler.PageGoneException.class);
    }

    @Test
    public void goneIsStillAnIOExceptionSoExistingCallersKeepWorking() throws Exception {
        // verifyStoredMetadata treats a vanished page during a write as a genuine failure, which it is.
        assertThatThrownBy(() -> fetch(clientReturning(403, "{}"))).isInstanceOf(IOException.class);
    }

    @Test
    public void aSuccessfulReadReturnsTheDataObject() throws Exception {
        final OkHttpClient client = clientReturning(200,
                "{\"status\":\"success\",\"data\":{\"id\":" + PAGE_ID + ",\"url\":\"https://a.example/x\"}}");
        assertThat(CustomGptIndexerNodeHandler.fetchPageMetadata(client, "1", PAGE_ID, "https://api.example")
                .optString("url", null)).isEqualTo("https://a.example/x");
    }
}
