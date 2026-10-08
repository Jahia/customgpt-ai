package org.jahia.community.modules.customgpt.indexer;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.jahia.community.modules.customgpt.settings.Config;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests the transport half of {@link JahiaRenderClient#render}, which {@code JahiaRenderClientTest} does not reach:
 * that class drives {@code extractOutput} on an envelope already in hand.
 *
 * <p>The distinction these tests exist to pin is the one a reader is most likely to collapse, because both sides
 * of it say "forbidden":
 *
 * <ul>
 *   <li><b>HTTP 401/403 from the endpoint</b> - the API token itself is refused. That is a CONFIGURATION fault
 *       affecting every node equally, so it must be raised. Treating it as "the indexer cannot see this page"
 *       would silently skip the entire corpus and report the run as a success over an empty index.</li>
 *   <li><b>HTTP 200 carrying a GraphQL access denial</b> - the token is fine and the account simply may not read
 *       this one node. That is expected and is skipped.</li>
 * </ul>
 *
 * <p>Nothing else in the suite proves the two are told apart, and the failure mode of getting it backwards is
 * silent in both directions: a bad token looks like a restricted corpus, and a restricted node looks like an
 * outage that fails the whole site.
 */
public class JahiaRenderClientRenderTest {

    private static final String PATH = "/sites/academy/home";
    private static final String ENDPOINT = "http://localhost:8080/modules/graphql";
    private static final String TOKEN = "jahia-pat-value";

    private static final String RENDERED =
            "{\"data\":{\"jcr\":{\"nodeByPath\":{\"renderedContent\":{\"output\":\"<html>hi</html>\"}}}}}";
    private static final String ACCESS_DENIED =
            "{\"errors\":[{\"message\":\"Permission denied\",\"errorType\":\"GqlAccessDeniedException\"}],\"data\":null}";

    /** The last request the client was asked to execute, so headers and body can be asserted. */
    private final AtomicReference<Request> sent = new AtomicReference<>();

    private OkHttpClient clientReturning(int status, String body) {
        final OkHttpClient client = mock(OkHttpClient.class);
        final Call call = mock(Call.class);
        when(client.newCall(any())).thenAnswer(invocation -> {
            final Request request = invocation.getArgument(0);
            sent.set(request);
            when(call.execute()).thenReturn(new Response.Builder()
                    .request(request).protocol(Protocol.HTTP_1_1).code(status).message("status " + status)
                    .body(ResponseBody.create(body, null))
                    .build());
            return call;
        });
        return client;
    }

    private static Config config(String endpoint, String token) {
        final Config config = mock(Config.class);
        when(config.getJahiaGraphqlEndpoint()).thenReturn(endpoint);
        when(config.getJahiaApiToken()).thenReturn(token);
        return config;
    }

    private String render(OkHttpClient client, String context) throws Exception {
        return JahiaRenderClient.render(client, config(ENDPOINT, TOKEN), PATH, "en", context);
    }

    private String bodySentAsText() throws IOException {
        final Buffer buffer = new Buffer();
        sent.get().body().writeTo(buffer);
        return buffer.readUtf8();
    }

    // ---- the skip decision ----

    @Test
    public void render_skipsWhenA200CarriesAGraphqlAccessDenial() {
        assertThatThrownBy(() -> render(clientReturning(200, ACCESS_DENIED), JahiaRenderClient.CONTEXT_PAGE))
                .isInstanceOf(JahiaRenderClient.NotVisibleToIndexerException.class)
                .hasMessageContaining(PATH);
    }

    @Test
    public void render_treatsAnHttp403AsAConfigurationFaultRatherThanASkip() {
        // A refused token affects every node. Skipping on it would empty the corpus and still report success.
        assertThatThrownBy(() -> render(clientReturning(403, "{}"), JahiaRenderClient.CONTEXT_PAGE))
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(JahiaRenderClient.NotVisibleToIndexerException.class)
                .hasMessageContaining("scoped to graphql");
    }

    @Test
    public void render_treatsAnHttp401TheSameWay() {
        assertThatThrownBy(() -> render(clientReturning(401, "{}"), JahiaRenderClient.CONTEXT_PAGE))
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(JahiaRenderClient.NotVisibleToIndexerException.class)
                .hasMessageContaining("scoped to graphql");
    }

    @Test
    public void render_treatsAServerErrorAsAFailureAndDoesNotAdviseCheckingTheToken() {
        assertThatThrownBy(() -> render(clientReturning(500, "{}"), JahiaRenderClient.CONTEXT_PAGE))
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(JahiaRenderClient.NotVisibleToIndexerException.class)
                .hasMessageContaining("HTTP 500");
        assertThat(sent.get()).isNotNull();
    }

    @Test
    public void render_returnsTheHtmlOnASuccessfulRender() throws Exception {
        assertThat(render(clientReturning(200, RENDERED), JahiaRenderClient.CONTEXT_PAGE)).contains("<html>hi</html>");
    }

    // ---- what actually goes on the wire ----

    @Test
    public void render_sendsTheTokenUnderTheApiTokenSchemeAndNoBasicCredentials() throws Exception {
        render(clientReturning(200, RENDERED), JahiaRenderClient.CONTEXT_PAGE);

        final String authorization = sent.get().header("Authorization");
        assertThat(authorization).isEqualTo("APIToken " + TOKEN);
        // The whole point of the move off the public URL: no Basic scheme on the wire, in any environment that
        // blocks it. Asserted rather than assumed, because a header set elsewhere would silently replace this one.
        assertThat(authorization).doesNotStartWith("Basic");
    }

    @Test
    public void render_asksTheEndpointForTheRequestedContextConfiguration() throws Exception {
        // Page versus module is not cosmetic: a page configuration on a content node resolves a template it does
        // not have. This asserts the choice survives as far as the request body, not merely as far as the caller.
        render(clientReturning(200, RENDERED), JahiaRenderClient.CONTEXT_MODULE);

        assertThat(bodySentAsText()).contains("\"context\":\"module\"").contains("\"path\":\"" + PATH + "\"");
    }

    @Test
    public void render_appliesTheConfiguredUserAgent() throws Exception {
        final Config config = config(ENDPOINT, TOKEN);
        when(config.getUserAgent()).thenReturn("jahia-customgpt-indexer");

        JahiaRenderClient.render(clientReturning(200, RENDERED), config, PATH, "en", JahiaRenderClient.CONTEXT_PAGE);

        assertThat(sent.get().header("User-Agent")).isEqualTo("jahia-customgpt-indexer");
    }

    // ---- refusing to run misconfigured ----

    @Test
    public void render_refusesWhenNoEndpointIsConfigured() {
        final OkHttpClient client = clientReturning(200, RENDERED);
        assertThatThrownBy(() -> JahiaRenderClient.render(client, config("", TOKEN), PATH, "en",
                JahiaRenderClient.CONTEXT_PAGE))
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(JahiaRenderClient.NotVisibleToIndexerException.class)
                .hasMessageContaining(PATH);
        assertThat(sent.get()).as("no call should be attempted without an endpoint").isNull();
    }

    @Test
    public void render_refusesWhenNoTokenIsConfigured() {
        final OkHttpClient client = clientReturning(200, RENDERED);
        assertThatThrownBy(() -> JahiaRenderClient.render(client, config(ENDPOINT, null), PATH, "en",
                JahiaRenderClient.CONTEXT_PAGE))
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(JahiaRenderClient.NotVisibleToIndexerException.class);
        assertThat(sent.get()).as("no unauthenticated call should be attempted").isNull();
    }
}
