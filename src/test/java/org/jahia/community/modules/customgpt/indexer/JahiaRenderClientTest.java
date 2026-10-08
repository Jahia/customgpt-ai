package org.jahia.community.modules.customgpt.indexer;

import org.json.JSONObject;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests how the GraphQL render envelope is interpreted.
 *
 * <p>The distinction this class exists to make is between two outcomes that look alike from a distance and must
 * never be conflated:
 *
 * <ul>
 *   <li><b>The indexing account may not read the node.</b> Expected - the account is deliberately restricted -
 *       so the node is skipped. Recording it as a failure would mark every site FAILED on every run.</li>
 *   <li><b>Something went wrong.</b> A real failure, which must be raised. The predecessor of this code fetched
 *       pages over HTTP and the equivalent cases (a redirect, a 5xx, an empty body) were once silently skipped,
 *       leaving runs that reported success over pages that were never uploaded.</li>
 * </ul>
 *
 * <p>Neither may be mistaken for content. An unauthorised HTTP fetch answered 200 with a login form, and that
 * text went into the corpus as if it were the page.
 *
 * <p>JUnit 4: the jahia-modules parent pins the {@code surefire-junit4} provider.
 */
public class JahiaRenderClientTest {

    private static final String PATH = "/sites/academy/home";

    private static JSONObject envelope(String json) {
        return new JSONObject(json);
    }

    @Test
    public void extractOutput_returnsTheRenderedHtml() throws Exception {
        final JSONObject body = envelope("{\"data\":{\"jcr\":{\"nodeByPath\":{\"renderedContent\":"
                + "{\"output\":\"<!DOCTYPE html><html><body>hello</body></html>\"}}}}}");

        assertThat(JahiaRenderClient.extractOutput(body, PATH)).contains("<body>hello</body>");
    }

    @Test
    public void extractOutput_skipsANodeJahiaHidesFromTheIndexingAccount() {
        // Measured on 8.2.3.2 against a page with ACL inheritance broken and no grant: Jahia does NOT report a
        // denial for content the caller may not read, it hides the node, so the render resolves to
        // PathNotFoundException. That is the real shape of "restricted" on the wire.
        //
        // Matched on the exception class name in the message. That is a Java type rather than prose, and it has
        // to be the discriminator because the classification here is the generic DataFetchingException - which a
        // template failure also carries, and which must stay a failure.
        final JSONObject body = envelope("{\"errors\":[{\"message\":"
                + "\"javax.jcr.PathNotFoundException: /sites/academy/home\","
                + "\"extensions\":{\"classification\":\"DataFetchingException\"}}],"
                + "\"data\":{\"jcr\":{\"nodeByPath\":null}}}");

        assertThatThrownBy(() -> JahiaRenderClient.extractOutput(body, PATH))
                .isInstanceOf(JahiaRenderClient.NotVisibleToIndexerException.class)
                .hasMessageContaining(PATH);
    }

    @Test
    public void extractOutput_skipsANodeThatDisappearedBetweenCollectionAndRender() {
        // Nodes are collected as root and rendered as the indexer, so a node deleted in between answers the same
        // way as a restricted one. Both mean there is nothing to fetch; neither should fail the site.
        final JSONObject body = envelope("{\"errors\":[{\"message\":"
                + "\"javax.jcr.PathNotFoundException: /sites/academy/home/gone\"}],"
                + "\"data\":{\"jcr\":{\"nodeByPath\":null}}}");

        assertThatThrownBy(() -> JahiaRenderClient.extractOutput(body, PATH))
                .isInstanceOf(JahiaRenderClient.NotVisibleToIndexerException.class);
    }

    @Test
    public void extractOutput_failsWhenTheAccountMayNotUseTheApiAtAll() {
        // GqlAccessDeniedException is not a per-node decision. It is raised at the ROOT jcr field when the
        // account lacks the api-access permission, so it applies to every node equally. Treating it as a skip
        // empties the index while reporting success - which is precisely what this module used to do.
        final JSONObject body = envelope("{\"errors\":[{\"message\":\"Permission denied\","
                + "\"extensions\":{\"classification\":\"GqlAccessDeniedException\"},"
                + "\"errorType\":\"GqlAccessDeniedException\"}],\"data\":null}");

        assertThatThrownBy(() -> JahiaRenderClient.extractOutput(body, PATH))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("api-access");
    }

    @Test
    public void extractOutput_failsOnAccessDenialCarriedOnlyByTheErrorType() {
        final JSONObject body = envelope("{\"errors\":[{\"message\":\"nope\","
                + "\"errorType\":\"GqlAccessDeniedException\"}],\"data\":null}");

        assertThatThrownBy(() -> JahiaRenderClient.extractOutput(body, PATH))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test
    public void extractOutput_raisesOnAnErrorThatIsNotAnAccessDecision() {
        // A broken query or an internal fault is a real failure and must not be mistaken for "restricted".
        final JSONObject body = envelope("{\"errors\":[{\"message\":\"Validation error\","
                + "\"extensions\":{\"classification\":\"ValidationError\"}}],\"data\":null}");

        assertThatThrownBy(() -> JahiaRenderClient.extractOutput(body, PATH))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("Validation error");
    }

    @Test
    public void extractOutput_raisesWhenTheNodeIsAbsentWithNoErrorAtAll() {
        final JSONObject body = envelope("{\"data\":{\"jcr\":{\"nodeByPath\":null}}}");

        assertThatThrownBy(() -> JahiaRenderClient.extractOutput(body, PATH))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining(PATH);
    }

    @Test
    public void extractOutput_raisesOnAnEmptyRenderRatherThanIndexingNothing() {
        // The old HTTP path once skipped an empty body silently, so a run reported success over a page that was
        // never uploaded. An empty render is a failure, not an empty page.
        final JSONObject body = envelope("{\"data\":{\"jcr\":{\"nodeByPath\":{\"renderedContent\":"
                + "{\"output\":\"\"}}}}}");

        assertThatThrownBy(() -> JahiaRenderClient.extractOutput(body, PATH))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test
    public void extractOutput_raisesOnAnEmptyEnvelope() {
        assertThatThrownBy(() -> JahiaRenderClient.extractOutput(envelope("{}"), PATH))
                .isInstanceOf(java.io.IOException.class);
    }

    // ---- render context selection ----

    @Test
    public void contextConstants_distinguishAPageFromContent() {
        // Not cosmetic: asking for a page configuration on a jmix:mainResource content node resolves a page
        // template it does not have, and Jahia answers TemplateNotFoundException. /sites/digitall/contents/
        // person-portrait-1 failed exactly that way until the two were told apart.
        assertThat(JahiaRenderClient.CONTEXT_PAGE).isEqualTo("page");
        assertThat(JahiaRenderClient.CONTEXT_MODULE).isEqualTo("module");
    }

    @Test
    public void extractOutput_raisesOnATemplateFailureRatherThanSkipping() {
        // A missing template is a real failure, not an access decision: it must not be quietly skipped, or a
        // node silently stops being indexed and the run still reports success.
        final JSONObject body = envelope("{\"errors\":[{\"message\":"
                + "\"RenderFilterException: TemplateNotFoundException: event\","
                + "\"extensions\":{\"classification\":\"DataFetchingException\"}}],\"data\":null}");

        assertThatThrownBy(() -> JahiaRenderClient.extractOutput(body, PATH))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("TemplateNotFoundException");
    }
}
