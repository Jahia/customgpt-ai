package org.jahia.community.modules.customgpt.indexer;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the context the orphan report puts in front of a human.
 *
 * <p>No predicate decides redundancy here, deliberately. The evidence that distinguished residue from unique
 * content on this corpus was a CONSTANT byte delta repeating down a cluster - 19617 across five 'Spring Bean
 * modifications' pages, ~9235 across three 'Upgrade ... on Jahia Cloud' ones. That pattern is legible only when
 * the rows are seen side by side, and no boolean or summary statistic reproduces it.
 *
 * <p>So the report prints the orphan's title and size beside its claimed twin's id and size, and lets the reader
 * see the column. An exact-URL predicate could only ever have returned "not redundant": 47 of 65 orphans have no
 * URL at all.
 */
public class OrphanReportContextTest {

    private static OrphanedPages.ProjectPage page(String id, String title, Long size) {
        final OrphanedPages.ProjectPage p = new OrphanedPages.ProjectPage(id, title, null);
        p.setFilesize(size);
        return p;
    }

    // --- title twins ---------------------------------------------------------

    @Test
    public void aSingleClaimedPageWithTheSameTitleIsReportedAsTheTwin() {
        final List<OrphanedPages.ProjectPage> claimed = Arrays.asList(
                page("100", "Spring Bean modifications in 7.3.0.1", 62550L),
                page("101", "Something else", 1L));

        final OrphanedPages.ProjectPage twin = OrphanedPages.findTitleTwin(
                page("1", "Spring Bean modifications in 7.3.0.1", 42933L), claimed);

        assertThat(twin).isNotNull();
        assertThat(twin.getId()).isEqualTo("100");
    }

    @Test
    public void anAmbiguousTitleYieldsNoTwin() {
        // This corpus has 'Overview' x13 and 'Introduction' x10. A 1:1 match on a specific title is evidence;
        // one of thirteen is not, and presenting it as a twin would invite exactly the wrong deletion.
        final List<OrphanedPages.ProjectPage> claimed = Arrays.asList(
                page("100", "Overview", 10L), page("101", "Overview", 20L));

        assertThat(OrphanedPages.findTitleTwin(page("1", "Overview", 5L), claimed)).isNull();
    }

    @Test
    public void noMatchingTitleYieldsNoTwin() {
        assertThat(OrphanedPages.findTitleTwin(page("1", "CVE Analysis", 5L),
                Collections.singletonList(page("100", "SBOMs", 10L)))).isNull();
    }

    @Test
    public void anOrphanWithNoTitleIsNotMatchedToAnythingByAccident() {
        assertThat(OrphanedPages.findTitleTwin(page("1", null, 5L),
                Collections.singletonList(page("100", null, 10L)))).isNull();
    }

    // --- node path hint ------------------------------------------------------
    //
    // A URL path beginning /home/, /contents/ or /files/ is a raw JCR path. Verified against paths present in
    // the corpus in both forms; NOT proven as an inverse, so the report labels it a hint.

    @Test
    public void aRawJcrPathYieldsANodePathHint() {
        assertThat(OrphanedPages.nodePathHint("academy", "https://academy.jahia.com/home/documentation"))
                .isEqualTo("/sites/academy/home/documentation");
    }

    @Test
    public void theHtmlSuffixIsStripped() {
        assertThat(OrphanedPages.nodePathHint("academy",
                "https://academy.jahia.com/contents/knowledge-base/2022/a-different-jahia-module.html"))
                .isEqualTo("/sites/academy/contents/knowledge-base/2022/a-different-jahia-module");
    }

    @Test
    public void aFilesPathYieldsAHint() {
        assertThat(OrphanedPages.nodePathHint("academy", "https://academy.jahia.com/files/training.pdf"))
                .isEqualTo("/sites/academy/files/training.pdf");
    }

    @Test
    public void aVanityUrlYieldsNoHint() {
        // 10 of 18 URL-bearing orphans are these, and there is no reliable inverse. Guessing would be worse
        // than saying nothing.
        assertThat(OrphanedPages.nodePathHint("academy",
                "https://academy.jahia.com/customer-center/security/cve-analysis")).isNull();
        assertThat(OrphanedPages.nodePathHint("academy",
                "https://academy.jahia.com/customer-center/jexperience/download")).isNull();
    }

    @Test
    public void anAbsentOrUnparseableUrlYieldsNoHint() {
        assertThat(OrphanedPages.nodePathHint("academy", null)).isNull();
        assertThat(OrphanedPages.nodePathHint("academy", "not a url")).isNull();
        assertThat(OrphanedPages.nodePathHint(null, "https://academy.jahia.com/home/x")).isNull();
    }
}
