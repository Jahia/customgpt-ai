package org.jahia.community.modules.customgpt.indexer;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import org.jahia.community.modules.customgpt.settings.Config;
import org.jahia.community.modules.customgpt.util.Utils;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.junit.Test;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests what the indexation path DOES with a node the indexing account may not read.
 *
 * <p>{@code JahiaRenderClientTest} and {@code JahiaRenderClientRenderTest} prove the render client raises
 * {@link JahiaRenderClient.NotVisibleToIndexerException} on the right envelope. Neither proves the consequence,
 * which is where the value of the distinction actually lands: the node is skipped WITHOUT being recorded as a
 * failure, while anything else is recorded.
 *
 * <p>That asymmetry is load-bearing. The indexing account is deliberately restricted, so unreadable content is
 * normal and expected on every run. Recording it as a failure would mark every site FAILED every time and bury
 * the handful of failures that are real - which is the same outcome, by a different route, as the silent-success
 * bugs this module has already been bitten by.
 *
 * <p>This path had never been exercised end to end: every node in the demo site is readable by the indexing
 * account, so no Cypress run has ever reached the catch block these tests drive.
 */
public class CustomGptIndexerNodeHandlerSkipTest {

    private static final String NODE = "/sites/acme/home/page";
    private static final String LANGUAGE = "en";

    private final OkHttpClient customGptClient = mock(OkHttpClient.class);
    private final Indexer indexer = mock(Indexer.class);

    /**
     * Drives {@code indexInSession} for one node whose render fails with {@code thrownByRender}.
     *
     * <p>Everything either side of the render is stubbed out: the sidecar lookup reports no previously indexed
     * page (so no delete is attempted) and URL building is mocked (it has its own tests). What is left running is
     * exactly the try/catch under test.
     *
     * @param isPage whether the node is a {@code jnt:page}, which selects the render context
     */
    private void indexWithRenderThrowing(Throwable thrownByRender, boolean isPage) throws Exception {
        final JCRNodeWrapper node = mock(JCRNodeWrapper.class);
        when(node.getPath()).thenReturn(NODE);
        when(node.getUrl()).thenReturn("/home/page.html");
        when(node.isFile()).thenReturn(false);
        when(node.isNodeType("jnt:page")).thenReturn(isPage);

        final JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        when(session.nodeExists(NODE)).thenReturn(true);
        when(session.getNode(NODE)).thenReturn(node);

        final JCRSiteNode siteNode = mock(JCRSiteNode.class);
        when(siteNode.getName()).thenReturn("acme");

        final Config config = mock(Config.class);
        when(config.isDryRun()).thenReturn(false);
        when(indexer.getCustomGptConfig()).thenReturn(config);

        // The sidecar lookup runs through JCRTemplate; null means "nothing indexed yet", so removeExistingPage
        // returns before issuing any HTTP call.
        final JCRTemplate template = mock(JCRTemplate.class);
        when(template.doExecuteWithSystemSessionAsUser(any(), any(), any(), any())).thenReturn(null);

        try (MockedStatic<JCRTemplate> templates = mockStatic(JCRTemplate.class);
                MockedStatic<Utils> utils = mockStatic(Utils.class);
                MockedStatic<JahiaRenderClient> render = mockStatic(JahiaRenderClient.class)) {
            templates.when(JCRTemplate::getInstance).thenReturn(template);
            utils.when(() -> Utils.getHostName(any(JCRSiteNode.class), any())).thenReturn("https://acme.example");
            utils.when(() -> Utils.encode(any(), any(), any())).thenReturn("/home/page.html");
            // Answer rather than thenThrow, so the context the caller chose is captured before the throw: a
            // MockedStatic cannot be verified once its scope has closed.
            render.when(() -> JahiaRenderClient.render(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
                renderedWithContext.set(invocation.getArgument(4));
                renderedNodePath.set(invocation.getArgument(2));
                throw thrownByRender;
            });

            CustomGptIndexerNodeHandler.indexInSession(session, customGptClient, mock(OkHttpClient.class),
                    node, siteNode, LANGUAGE, "https://api.example", indexer, null);
        }
    }

    /** The {@code contextConfiguration} and node path the render was actually asked for. */
    private final AtomicReference<String> renderedWithContext = new AtomicReference<>();
    private final AtomicReference<String> renderedNodePath = new AtomicReference<>();

    // ---- the asymmetry ----

    @Test
    public void aNodeTheIndexerMayNotReadIsSkippedWithoutBeingRecordedAsAFailure() throws Exception {
        indexWithRenderThrowing(new JahiaRenderClient.NotVisibleToIndexerException(NODE + " is not readable"), true);

        verify(indexer, never()).recordFailure(any(), any(), any());
    }

    @Test
    public void aRealFailureIsStillRecordedSoTheRunCannotReportSuccessOverIt() throws Exception {
        final IOException cause = new IOException("Jahia GraphQL returned HTTP 500");

        indexWithRenderThrowing(cause, true);

        verify(indexer).recordFailure(NODE, LANGUAGE, cause);
    }

    @Test
    public void aSkippedNodeIsNeverUploadedAsContent() throws Exception {
        // The defect this replaced: an unauthorised HTTP fetch answered 200 with a login form, and that text was
        // uploaded as if it were the page. A skip must upload nothing at all.
        indexWithRenderThrowing(new JahiaRenderClient.NotVisibleToIndexerException("denied"), true);

        verify(customGptClient, never()).newCall(any());
    }

    // ---- the render context the skip was decided on ----

    @Test
    public void aPageIsRenderedWithThePageContext() throws Exception {
        indexWithRenderThrowing(new JahiaRenderClient.NotVisibleToIndexerException("denied"), true);

        assertThat(renderedNodePath.get()).isEqualTo(NODE);
        assertThat(renderedWithContext.get()).isEqualTo(JahiaRenderClient.CONTEXT_PAGE);
    }

    @Test
    public void contentThatIsNotAPageIsRenderedWithTheModuleContext() throws Exception {
        // Asking for a page configuration on a jmix:mainResource node resolves a page template it does not have,
        // and Jahia answers TemplateNotFoundException - a failure, not a skip.
        indexWithRenderThrowing(new JahiaRenderClient.NotVisibleToIndexerException("denied"), false);

        assertThat(renderedWithContext.get()).isEqualTo(JahiaRenderClient.CONTEXT_MODULE);
    }

    // ---- the type contract that keeps the two apart ----

    @Test
    public void theSkipExceptionIsNotAnIOExceptionSoFailureHandlersCannotSwallowIt() {
        // indexInSession catches the skip first and IOException second, so the ordering alone would survive this
        // becoming an IOException subclass. Every OTHER caller that catches IOException would not: the skip would
        // quietly become a recorded failure again. Pinned here because the compiler will not notice.
        assertThat(IOException.class.isAssignableFrom(JahiaRenderClient.NotVisibleToIndexerException.class))
                .as("a skip must not be catchable as an IOException")
                .isFalse();
        assertThat(new JahiaRenderClient.NotVisibleToIndexerException("x")).isInstanceOf(Exception.class);
    }
}
