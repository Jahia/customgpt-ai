package org.jahia.community.modules.customgpt.util;

import org.jahia.osgi.BundleUtils;
import org.jahia.services.render.RenderContext;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.seo.urlrewrite.UrlRewriteService;
import org.jahia.services.usermanager.JahiaUser;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the thread-local user binding around outbound URL rewriting.
 *
 * <p>Jahia resolves vanity URLs inside {@code rewriteOutbound} through a JCR lookup that reads
 * {@code JCRSessionFactory.getCurrentUser()}. Indexation runs on a pooled executor thread, and
 * {@code doExecuteWithSystemSessionAsUser} binds the SESSION's user without binding that thread-local. The lookup
 * therefore failed, the raw {@code .html} path was used, Jahia answered it with a 302 to the vanity URL, and this
 * module does not follow redirects - so the page was never indexed and nothing recorded a failure.
 */
public class UtilsCurrentUserTest {

    private static final String URI = "/sites/acme/home/page.html";

    /** Captures the thread-local current user as observed from inside rewriteOutbound. */
    private static class Probe {
        JahiaUser seenInside;
        boolean called;
    }

    private void runEncode(JahiaUser previous, JahiaUser bind, Probe probe, RuntimeException failWith) throws Exception {
        final List<JahiaUser> assignments = new ArrayList<>();
        final JCRSessionFactory factory = mock(JCRSessionFactory.class);
        final JahiaUser[] current = {previous};
        when(factory.getCurrentUser()).thenAnswer(inv -> current[0]);
        org.mockito.Mockito.doAnswer(inv -> {
            final JahiaUser u = inv.getArgument(0);
            current[0] = u;
            assignments.add(u);
            return null;
        }).when(factory).setCurrentUser(any());

        final UrlRewriteService rewrite = mock(UrlRewriteService.class);
        when(rewrite.rewriteOutbound(any(), any(), any())).thenAnswer(inv -> {
            probe.called = true;
            probe.seenInside = factory.getCurrentUser();
            if (failWith != null) {
                throw failWith;
            }
            return "/home/page";
        });

        final RenderContext renderContext = mock(RenderContext.class);

        try (MockedStatic<JCRSessionFactory> factories = mockStatic(JCRSessionFactory.class);
                MockedStatic<BundleUtils> bundles = mockStatic(BundleUtils.class)) {
            factories.when(JCRSessionFactory::getInstance).thenReturn(factory);
            bundles.when(() -> BundleUtils.getOsgiService(UrlRewriteService.class, null)).thenReturn(rewrite);
            Utils.encode(URI, renderContext, bind);
        } finally {
            this.lastAssignments = assignments;
            this.lastCurrent = current[0];
        }
    }

    private List<JahiaUser> lastAssignments;
    private JahiaUser lastCurrent;

    @Test
    public void encode_bindsTheGivenUserForTheDurationOfTheRewrite() throws Exception {
        final JahiaUser root = mock(JahiaUser.class);
        final Probe probe = new Probe();

        runEncode(null, root, probe, null);

        assertThat(probe.called).isTrue();
        // The whole point: the rewrite must see the user, otherwise the vanity lookup silently degrades.
        assertThat(probe.seenInside).isSameAs(root);
    }

    @Test
    public void encode_restoresThePreviousUserAfterwards() throws Exception {
        final JahiaUser previous = mock(JahiaUser.class);
        final JahiaUser root = mock(JahiaUser.class);

        runEncode(previous, root, new Probe(), null);

        // These threads are pooled and reused; leaving a user bound leaks an identity into the next task.
        assertThat(lastCurrent).isSameAs(previous);
        assertThat(lastAssignments).containsExactly(root, previous);
    }

    @Test
    public void encode_restoresANullPreviousUser() throws Exception {
        final JahiaUser root = mock(JahiaUser.class);

        runEncode(null, root, new Probe(), null);

        assertThat(lastCurrent).isNull();
        assertThat(lastAssignments).containsExactly(root, null);
    }

    @Test
    public void encode_restoresThePreviousUserEvenWhenTheRewriteThrows() {
        final JahiaUser previous = mock(JahiaUser.class);
        final JahiaUser root = mock(JahiaUser.class);

        assertThatThrownBy(() -> runEncode(previous, root, new Probe(), new IllegalStateException("boom")))
                .isInstanceOf(IllegalStateException.class);

        assertThat(lastCurrent).isSameAs(previous);
        assertThat(lastAssignments).containsExactly(root, previous);
    }
}
