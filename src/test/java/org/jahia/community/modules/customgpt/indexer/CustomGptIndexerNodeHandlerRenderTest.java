package org.jahia.community.modules.customgpt.indexer;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.jahia.community.modules.customgpt.settings.Config;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.usermanager.JahiaUser;
import org.junit.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the silently-skipped render.
 *
 * <p>When Jahia did not return the page, {@code indexJahiaPage} logged "Impossible to retrieve content from {}"
 * at WARN and returned. Nothing was recorded, so the enclosing run still reported success and the site was stamped
 * as indexed over content that was never uploaded. In production this was the visible end of the vanity-URL defect:
 * the raw {@code .html} path produced a 302 that this module does not follow, and every such page vanished from the
 * corpus without a single failure being counted.
 */
public class CustomGptIndexerNodeHandlerRenderTest {

    private static final String URL = "https://acme.example/home/page.html";
    private static final String NODE = "/sites/acme/home/page";

    /** Drives indexJahiaPage against a Jahia render answering {@code status}, optionally with no body. */
    private void render(int status, boolean withBody) throws Exception {
        final Response.Builder builder = new Response.Builder()
                .request(new Request.Builder().url(URL).build())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("status " + status);
        if (withBody) {
            builder.body(okhttp3.ResponseBody.create("<html></html>", null));
        }

        final OkHttpClient jahiaClient = mock(OkHttpClient.class);
        final Call call = mock(Call.class);
        when(jahiaClient.newCall(any())).thenReturn(call);
        when(call.execute()).thenReturn(builder.build());

        final Config config = mock(Config.class);
        final Indexer indexer = mock(Indexer.class);
        when(indexer.getCustomGptConfig()).thenReturn(config);

        final JCRNodeWrapper liveNode = mock(JCRNodeWrapper.class);
        when(liveNode.getPath()).thenReturn(NODE);

        CustomGptIndexerNodeHandler.indexJahiaPage(mock(OkHttpClient.class), jahiaClient, indexer,
                "https://api.example", mock(JahiaUser.class), liveNode, URL, "en");
    }

    @Test
    public void aRedirectIsRaisedRatherThanSilentlySkipped() {
        // 302 is the exact production shape: the raw .html path redirects to the vanity URL and is not followed.
        assertThatThrownBy(() -> render(302, true))
                .isInstanceOf(IOException.class)
                .hasMessageContaining(URL);
    }

    @Test
    public void aServerErrorIsRaisedRatherThanSilentlySkipped() {
        assertThatThrownBy(() -> render(500, true))
                .isInstanceOf(IOException.class)
                .hasMessageContaining(URL);
    }

    @Test
    public void aSuccessfulResponseWithNoBodyIsRaisedRatherThanSilentlySkipped() {
        assertThatThrownBy(() -> render(200, false))
                .isInstanceOf(IOException.class)
                .hasMessageContaining(URL);
    }
}
