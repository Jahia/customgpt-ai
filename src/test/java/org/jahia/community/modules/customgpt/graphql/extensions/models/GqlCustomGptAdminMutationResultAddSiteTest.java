package org.jahia.community.modules.customgpt.graphql.extensions.models;

import org.jahia.community.modules.customgpt.CustomGptConstants;
import org.jahia.modules.graphql.provider.dxm.DataFetchingException;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.junit.After;
import org.junit.Test;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GqlCustomGptAdminMutationResult#addSite(String)}:
 * <ul>
 *   <li>U6 — site-key input validation against path traversal / injection, checked <em>before</em> any
 *       JCR session is touched.</li>
 *   <li>F5 — the mixin-add / idempotent {@code Status} logic for a valid site key.</li>
 * </ul>
 */
public class GqlCustomGptAdminMutationResultAddSiteTest {

    private MockedStatic<JCRSessionFactory> jcrSessionFactoryStatic;
    private MockedStatic<JCRTemplate> jcrTemplateStatic;

    @After
    public void tearDown() {
        if (jcrSessionFactoryStatic != null) {
            jcrSessionFactoryStatic.close();
        }
        if (jcrTemplateStatic != null) {
            jcrTemplateStatic.close();
        }
    }

    // ---- U6: invalid site keys are rejected before any JCR path is built / session touched ----

    @Test
    public void addSite_pathTraversalSiteKey_throwsBeforeTouchingJcr() {
        jcrSessionFactoryStatic = mockStatic(JCRSessionFactory.class, invocation -> {
            throw new AssertionError("JCRSessionFactory must not be touched for an invalid site key");
        });

        assertThatThrownBy(() -> new GqlCustomGptAdminMutationResult().addSite("../../etc/passwd"))
                .isInstanceOf(DataFetchingException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void addSite_siteKeyWithSpace_throws() {
        jcrSessionFactoryStatic = mockStatic(JCRSessionFactory.class, invocation -> {
            throw new AssertionError("JCRSessionFactory must not be touched for an invalid site key");
        });

        assertThatThrownBy(() -> new GqlCustomGptAdminMutationResult().addSite("foo bar"))
                .isInstanceOf(DataFetchingException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void addSite_siteKeyWithShellMetacharacters_throws() {
        jcrSessionFactoryStatic = mockStatic(JCRSessionFactory.class, invocation -> {
            throw new AssertionError("JCRSessionFactory must not be touched for an invalid site key");
        });

        assertThatThrownBy(() -> new GqlCustomGptAdminMutationResult().addSite("foo;rm -rf"))
                .isInstanceOf(DataFetchingException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void addSite_nullSiteKey_throws() {
        jcrSessionFactoryStatic = mockStatic(JCRSessionFactory.class, invocation -> {
            throw new AssertionError("JCRSessionFactory must not be touched for a null site key");
        });

        assertThatThrownBy(() -> new GqlCustomGptAdminMutationResult().addSite(null))
                .isInstanceOf(DataFetchingException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    // ---- F5: valid site key — mixin-add / idempotent Status logic ----

    private void grantAdminAndSiteAdminPermission(JCRSessionWrapper permissionSession, JCRNodeWrapper rootNode, JCRNodeWrapper siteNode) throws Exception {
        when(permissionSession.getNode(CustomGptConstants.PATH_DELIMITER)).thenReturn(rootNode);
        when(rootNode.hasPermission("customGptAdmin")).thenReturn(true);
        when(permissionSession.getNode(CustomGptConstants.PATH_SITES + "acme")).thenReturn(siteNode);
        when(siteNode.hasPermission(CustomGptConstants.PERM_SITE_ADMIN)).thenReturn(true);

        final JCRSessionFactory factory = mock(JCRSessionFactory.class);
        when(factory.getCurrentUserSession()).thenReturn(permissionSession);
        jcrSessionFactoryStatic = mockStatic(JCRSessionFactory.class);
        jcrSessionFactoryStatic.when(JCRSessionFactory::getInstance).thenReturn(factory);
    }

    @Test
    public void addSite_siteNotYetIndexable_addsMixinAndReturnsSuccessful() throws Exception {
        final JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        final JCRNodeWrapper rootNode = mock(JCRNodeWrapper.class);
        final JCRNodeWrapper siteNode = mock(JCRNodeWrapper.class);
        when(siteNode.isNodeType(CustomGptConstants.MIX_INDEXABLE_SITE)).thenReturn(false);

        grantAdminAndSiteAdminPermission(session, rootNode, siteNode);

        // The permission-check session and the "doExecuteWithSystemSession" callback session are the same
        // mock here: checkAdminPermission(path, perm) is called against
        // JCRSessionFactory.getInstance().getCurrentUserSession(), and separately the mutation opens a
        // *system* session via JCRTemplate for the actual mixin mutation — model both against the same
        // node mocks for path "/sites/acme" so behaviour is consistent regardless of which session queries it.
        final JCRTemplate template = mock(JCRTemplate.class);
        when(template.doExecuteWithSystemSession(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            final org.jahia.services.content.JCRCallback<?> callback = invocation.getArgument(0);
            return callback.doInJCR(session);
        });
        jcrTemplateStatic = mockStatic(JCRTemplate.class);
        jcrTemplateStatic.when(JCRTemplate::getInstance).thenReturn(template);

        final String result = new GqlCustomGptAdminMutationResult().addSite("acme");

        assertThat(result).isEqualTo(GqlCustomGptAdminMutationResult.Status.SUCCESSFUL.getValue());
        verify(siteNode).addMixin(CustomGptConstants.MIX_INDEXABLE_SITE);
        verify(session).save();
    }

    @Test
    public void addSite_siteAlreadyIndexable_returnsExistAlready_noRedundantSave() throws Exception {
        final JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        final JCRNodeWrapper rootNode = mock(JCRNodeWrapper.class);
        final JCRNodeWrapper siteNode = mock(JCRNodeWrapper.class);
        when(siteNode.isNodeType(CustomGptConstants.MIX_INDEXABLE_SITE)).thenReturn(true);

        grantAdminAndSiteAdminPermission(session, rootNode, siteNode);

        final JCRTemplate template = mock(JCRTemplate.class);
        when(template.doExecuteWithSystemSession(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            final org.jahia.services.content.JCRCallback<?> callback = invocation.getArgument(0);
            return callback.doInJCR(session);
        });
        jcrTemplateStatic = mockStatic(JCRTemplate.class);
        jcrTemplateStatic.when(JCRTemplate::getInstance).thenReturn(template);

        final String result = new GqlCustomGptAdminMutationResult().addSite("acme");

        assertThat(result).isEqualTo(GqlCustomGptAdminMutationResult.Status.EXISTALREADY.getValue());
        verify(siteNode, never()).addMixin(org.mockito.ArgumentMatchers.anyString());
        verify(session, never()).save();
    }
}
