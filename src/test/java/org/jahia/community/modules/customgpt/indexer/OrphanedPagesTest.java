package org.jahia.community.modules.customgpt.indexer;

import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for orphan detection: pages in the CustomGPT project that no mapping node claims.
 *
 * <p>Roughly 60 pages in the production corpus answer 404 on their stored URL - content that was indexed and then
 * removed or moved without the corpus being updated, including a security advisory. Their URL is present, just
 * dead, so {@link PageUrlRepair} leaves them alone.
 *
 * <p>Detection is a repository fact rather than a network observation. Mapping nodes are children of the content
 * node, so deleting content deletes its mapping; a project page whose id no mapping node holds is a page nothing
 * in Jahia accounts for. That avoids false positives from the 26.7% of the corpus behind auth, whose redirect and
 * status behaviour cannot be observed anonymously at all.
 */
public class OrphanedPagesTest {

    private static OrphanedPages.ProjectPage page(String id) {
        return new OrphanedPages.ProjectPage(id, "Title " + id, "https://academy.example/" + id);
    }

    private static Set<String> known(String... ids) {
        return new HashSet<>(Arrays.asList(ids));
    }

    @Test
    public void aPageNoMappingNodeClaimsIsAnOrphan() {
        final List<OrphanedPages.ProjectPage> orphans =
                OrphanedPages.findOrphans(known("1", "2"), Arrays.asList(page("1"), page("2"), page("3")));

        assertThat(orphans).extracting(p -> p.getId()).containsExactly("3");
    }

    @Test
    public void everyClaimedPageIsLeftAlone() {
        final List<OrphanedPages.ProjectPage> orphans =
                OrphanedPages.findOrphans(known("1", "2", "3"), Arrays.asList(page("1"), page("2"), page("3")));

        assertThat(orphans).isEmpty();
    }

    @Test
    public void aMappingNodeWithNoMatchingProjectPageIsNotAnOrphan() {
        // The inverse case: Jahia thinks it indexed something the project no longer has. That is a re-index
        // candidate, not something to delete, and must never appear in this list.
        final List<OrphanedPages.ProjectPage> orphans =
                OrphanedPages.findOrphans(known("1", "2", "99"), Collections.singletonList(page("1")));

        assertThat(orphans).isEmpty();
    }

    @Test
    public void anEmptyProjectYieldsNoOrphans() {
        assertThat(OrphanedPages.findOrphans(known("1"), Collections.emptyList())).isEmpty();
    }

    // --- the guard that matters more than the feature ------------------------

    @Test
    public void sweepingIsRefusedWhenJahiaClaimsNothingAtAll() {
        // If the JCR side comes back empty - a failed query, a partially restored repository, a module that has
        // never indexed - then EVERY page looks orphaned and a sweep would empty the corpus. Refuse instead.
        assertThatThrownBy(() -> OrphanedPages.assertSafeToSweep(Collections.emptySet()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no mapping nodes");
    }

    @Test
    public void sweepingIsAllowedWhenJahiaClaimsAtLeastOnePage() {
        assertThatCode(() -> OrphanedPages.assertSafeToSweep(known("1"))).doesNotThrowAnyException();
    }
}
