package org.jahia.community.modules.customgpt.indexer;

import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests that orphaned pages are never deleted wholesale.
 *
 * <p>The detection is sound - a page no mapping node claims is a page nothing in Jahia accounts for - but the
 * remedy is not. That state has two causes with opposite remedies:
 *
 * <ul>
 *   <li>content was deleted in Jahia, and the page is residue to remove;</li>
 *   <li>the mapping node was lost while the content survives, and the page is the ONLY copy of that content in
 *       the corpus. Deleting it destroys it; the remedy is to re-index the node so it regains a mapping.</li>
 * </ul>
 *
 * <p>Measured on production: of 65 orphans, <strong>0</strong> were redundant. 18 held substantive unique content
 * - CVE analyses, SBOMs, VEX documents, security advisories, compatibility matrices - with working citation
 * links. A wholesale sweep would have removed all of it.
 *
 * <p>Establishing redundancy needs the stored URL of every claimed page, which is thousands of metadata reads and
 * cannot be done inside one request. So the tool does not guess: it reports, and deletes only page ids a human
 * has passed back to it.
 */
public class OrphanDeletionScopeTest {

    @Test
    public void deletingWithoutAnExplicitListIsRefused() {
        assertThatThrownBy(() -> OrphanedPages.assertDeletionIsScoped(null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("pageIds");
        assertThatThrownBy(() -> OrphanedPages.assertDeletionIsScoped(Collections.emptyList()))
                .isInstanceOf(IOException.class);
    }

    @Test
    public void deletingAReviewedListIsAllowed() {
        assertThatCode(() -> OrphanedPages.assertDeletionIsScoped(Arrays.asList("86704738")))
                .doesNotThrowAnyException();
    }

    @Test
    public void onlyTheNamedOrphansAreDeleted() {
        final List<OrphanedPages.ProjectPage> orphans = Arrays.asList(
                new OrphanedPages.ProjectPage("1", "CVE Analysis", "https://a.example/cve"),
                new OrphanedPages.ProjectPage("2", "SBOMs", "https://a.example/sboms"),
                new OrphanedPages.ProjectPage("3", "VEX", "https://a.example/vex"));

        assertThat(OrphanedPages.selectForDeletion(orphans, Arrays.asList("2")))
                .extracting(OrphanedPages.ProjectPage::getId).containsExactly("2");
    }

    @Test
    public void anIdThatIsNotAnOrphanIsNotDeletedJustBecauseItWasNamed() {
        // Naming an id does not make it deletable; it still has to have come out of the detection.
        final List<OrphanedPages.ProjectPage> orphans = Collections.singletonList(
                new OrphanedPages.ProjectPage("1", "CVE Analysis", "https://a.example/cve"));

        assertThat(OrphanedPages.selectForDeletion(orphans, Arrays.asList("1", "999")))
                .extracting(OrphanedPages.ProjectPage::getId).containsExactly("1");
    }
}
