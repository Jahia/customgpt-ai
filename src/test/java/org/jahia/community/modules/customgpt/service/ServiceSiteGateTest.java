package org.jahia.community.modules.customgpt.service;

import org.jahia.community.modules.customgpt.CustomGptConstants;
import org.jahia.community.modules.customgpt.indexer.Indexer;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.junit.Test;

import javax.jcr.RepositoryException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the site-registration gate on the indexation path.
 *
 * <p>{@code acceptablePathToIndex} checks the shape of a path and nothing else, so every site under
 * {@code /sites/} was indexed on publish regardless of whether an administrator had ever registered it
 * with {@code addSite}. That was measurable in production: a page from an unregistered site
 * ({@code store.jahia.com}) sat in a corpus that was supposed to hold one site's content.
 *
 * <p>The gate fails CLOSED. Not indexing a page that should have been indexed is repaired by a re-index;
 * indexing a page that should not have been requires finding it in a corpus that offers no way to filter
 * by origin, and deleting it by hand.
 */
public class ServiceSiteGateTest {

    private static final String REGISTERED = "/sites/academy";
    private static final String UNREGISTERED = "/sites/store";

    private static Service newService() throws Exception {
        return Service.class.getDeclaredConstructor().newInstance();
    }

    // --- site key extraction -------------------------------------------------

    @Test
    public void siteKeyOf_extractsTheKeyFromADescendantPath() {
        assertThat(Service.siteKeyOf("/sites/academy/home/page")).isEqualTo("academy");
    }

    @Test
    public void siteKeyOf_extractsTheKeyFromTheSiteNodeItself() {
        assertThat(Service.siteKeyOf("/sites/academy")).isEqualTo("academy");
    }

    @Test
    public void siteKeyOf_rejectsATrashPath() {
        // A trash path belongs to a delete, which is deliberately ungated; it can never name a site to index.
        assertThat(Service.siteKeyOf("/trash-20260513/academy/home")).isNull();
    }

    @Test
    public void siteKeyOf_rejectsAPathOutsideSites() {
        assertThat(Service.siteKeyOf("/modules/customgpt-ai/x")).isNull();
    }

    @Test
    public void siteKeyOf_rejectsAnEmptySiteKey() {
        assertThat(Service.siteKeyOf("/sites/")).isNull();
        assertThat(Service.siteKeyOf("/sites")).isNull();
    }

    // --- registration check --------------------------------------------------

    /** A session whose {@code /sites/<key>} node exists and carries (or does not carry) the mixin. */
    private static JCRSessionWrapper sessionWithSite(String sitePath, boolean registered) throws RepositoryException {
        final JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        final JCRNodeWrapper siteNode = mock(JCRNodeWrapper.class);
        when(session.nodeExists(sitePath)).thenReturn(true);
        when(session.getNode(sitePath)).thenReturn(siteNode);
        when(siteNode.isNodeType(CustomGptConstants.MIX_INDEXABLE_SITE)).thenReturn(registered);
        return session;
    }

    @Test
    public void isInRegisteredSite_acceptsASiteCarryingTheMixin() throws Exception {
        final JCRSessionWrapper session = sessionWithSite(REGISTERED, true);
        assertThat(newService().isInRegisteredSite(REGISTERED + "/home/page", session)).isTrue();
    }

    @Test
    public void isInRegisteredSite_rejectsASiteWithoutTheMixin() throws Exception {
        // The production defect: store.jahia.com was never registered, yet its home page reached the corpus.
        final JCRSessionWrapper session = sessionWithSite(UNREGISTERED, false);
        assertThat(newService().isInRegisteredSite(UNREGISTERED + "/home", session)).isFalse();
    }

    @Test
    public void isInRegisteredSite_rejectsAMissingSiteNode() throws Exception {
        final JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        when(session.nodeExists(UNREGISTERED)).thenReturn(false);
        assertThat(newService().isInRegisteredSite(UNREGISTERED + "/home", session)).isFalse();
    }

    @Test
    public void isInRegisteredSite_failsClosedWhenTheRepositoryThrows() throws Exception {
        final JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        doThrow(new RepositoryException("boom")).when(session).nodeExists(REGISTERED);
        assertThat(newService().isInRegisteredSite(REGISTERED + "/home", session)).isFalse();
    }

    @Test
    public void isInRegisteredSite_rejectsAPathWithNoResolvableSite() throws Exception {
        final JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        assertThat(newService().isInRegisteredSite("/modules/whatever", session)).isFalse();
    }

    // --- composition ---------------------------------------------------------
    //
    // acceptableToIndex is what the four index branches of dispatchOperation call. These pin that it is a
    // conjunction of BOTH checks: dropping either one is the regression that put a foreign page in the corpus.

    private static Indexer indexerOn(JCRSessionWrapper session) throws RepositoryException {
        final Indexer indexer = mock(Indexer.class);
        when(indexer.getSystemSession()).thenReturn(session);
        return indexer;
    }

    @Test
    public void acceptableToIndex_acceptsAWellShapedPathInARegisteredSite() throws Exception {
        final Indexer indexer = indexerOn(sessionWithSite(REGISTERED, true));
        assertThat(newService().acceptableToIndex(REGISTERED + "/home/page", indexer)).isTrue();
    }

    @Test
    public void acceptableToIndex_rejectsAWellShapedPathInAnUnregisteredSite() throws Exception {
        // Kills the mutation "call acceptablePathToIndex only", i.e. the state of the code before this fix.
        final Indexer indexer = indexerOn(sessionWithSite(UNREGISTERED, false));
        assertThat(newService().acceptableToIndex(UNREGISTERED + "/home", indexer)).isFalse();
    }

    @Test
    public void acceptableToIndex_rejectsABadlyShapedPathInARegisteredSite() throws Exception {
        // Kills the mutation "call isInRegisteredSite only" - the shape check still has to run.
        final Indexer indexer = indexerOn(sessionWithSite(REGISTERED, true));
        final String propertyPath = REGISTERED + "/home/" + CustomGptConstants.PROP_CUSTOM_GPT_PAGE_ID;
        assertThat(newService().acceptableToIndex(propertyPath, indexer)).isFalse();
    }

    @Test
    public void acceptableToIndex_failsClosedWhenTheSessionCannotBeOpened() throws Exception {
        final Indexer indexer = mock(Indexer.class);
        doThrow(new RepositoryException("no session")).when(indexer).getSystemSession();
        assertThat(newService().acceptableToIndex(REGISTERED + "/home", indexer)).isFalse();
    }
}
