package org.jahia.community.modules.customgpt.indexer;

import java.io.IOException;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jahia.community.modules.customgpt.settings.Config;
import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.junit.Test;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the orphan defect: a failed DELETE followed by an unconditional add.
 *
 * <p>{@code removeExistingPage} discarded the delete result, so re-publishing a page whose delete had failed left
 * the previous page in the CustomGPT project. An audit of the academy corpus found 223 such pages, up to 17 copies
 * of one security advisory, with no log line anywhere. These tests pin that the caller now acts on the status.
 */
public class CustomGptIndexerNodeHandlerRemoveTest {

    private static final String NODE = "/sites/acme/home/page";
    private static final String URL = "https://acme.example/home/page.html";

    /** Drives removeExistingPage with a sidecar page id present and a DELETE answering {@code status}. */
    private void removeWithDeleteStatus(int status) throws Exception {
        final OkHttpClient client = mock(OkHttpClient.class);
        final Call call = mock(Call.class);
        when(client.newCall(any())).thenReturn(call);
        when(call.execute()).thenReturn(new Response.Builder()
                .request(new Request.Builder().url("https://api.example/projects/1/pages/42").build())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("status " + status)
                .body(ResponseBody.create("{}", null))
                .build());

        final Config config = mock(Config.class);
        when(config.getCustomGptProjectId()).thenReturn("1");
        final Indexer indexer = mock(Indexer.class);
        when(indexer.getCustomGptConfig()).thenReturn(config);

        // The sidecar lookup runs through JCRTemplate; return an existing page id so a delete is attempted.
        final JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        when(session.nodeExists(any())).thenReturn(false);
        final JCRTemplate template = mock(JCRTemplate.class);
        when(template.doExecuteWithSystemSessionAsUser(any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    final JCRCallback<?> callback = invocation.getArgument(3);
                    callback.doInJCR(session);
                    return "42";
                });

        try (MockedStatic<JCRTemplate> statics = mockStatic(JCRTemplate.class)) {
            statics.when(JCRTemplate::getInstance).thenReturn(template);
            CustomGptIndexerNodeHandler.removeExistingPage(client, indexer, "https://api.example", null, NODE, URL, "en");
        }
    }

    @Test
    public void removeExistingPage_proceedsWhenTheDeleteSucceeded() {
        assertThatCode(() -> removeWithDeleteStatus(200)).doesNotThrowAnyException();
    }

    /** A stale sidecar id answers 403 on this API; nothing was left behind, so re-indexing must not be blocked. */
    @Test
    public void removeExistingPage_proceedsWhenThePageWasAlreadyGone() {
        assertThatCode(() -> removeWithDeleteStatus(403)).doesNotThrowAnyException();
    }

    /**
     * The defect. On any status that may have left the page in place, the caller must abort rather than add a
     * replacement - adding one is what accumulated the duplicates.
     */
    @Test
    public void removeExistingPage_abortsRatherThanDuplicateWhenTheDeleteMayHaveFailed() {
        assertThatThrownBy(() -> removeWithDeleteStatus(429))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("duplicate");
        assertThatThrownBy(() -> removeWithDeleteStatus(500))
                .isInstanceOf(IOException.class);
    }
}
