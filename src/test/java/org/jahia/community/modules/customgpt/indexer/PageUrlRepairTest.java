package org.jahia.community.modules.customgpt.indexer;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the predicate that selects pages to repair.
 *
 * <p>The shape is measured, not assumed: all 47 null-URL pages in the production corpus return the identical
 * five-key set {@code [description, id, image, title, url]} with {@code url} set to JSON null. The key is never
 * absent and never empty, and {@code description}/{@code image} are null on healthy pages too - so {@code url} is
 * the only field that distinguishes a dropped write.
 *
 * <p>A URL that is present but no longer canonical is repaired too. 16 pages under {@code /jahia-cloud/latest/}
 * store a path answering 301 to a different URL, because {@code latest} is an alias segment in that tree; a
 * measured sample put the corpus-wide redirect rate at ~0.7%, so this is a targeted case rather than a sweep.
 */
public class PageUrlRepairTest {

    private static final String CANONICAL = "https://academy.example/documentation/jahia-cloud/jahia-cloud/about";

    // --- deciding whether to rewrite ----------------------------------------

    @Test
    public void aPageAlreadyStoringTheCanonicalUrlIsLeftAlone() {
        assertThat(PageUrlRepair.needsRepair(CANONICAL, CANONICAL)).isFalse();
    }

    @Test
    public void aPageWithNoStoredUrlIsRepaired() {
        assertThat(PageUrlRepair.needsRepair(null, CANONICAL)).isTrue();
    }

    @Test
    public void aPageStoringAnAliasPathThatRedirectsIsRepaired() {
        // The measured /jahia-cloud/latest/ shape: stored path 301s to the canonical one, and the redirect also
        // normalises case and spaces, so this is a real vanity mapping rather than trailing-slash noise.
        final String alias = "https://academy.example/home/documentation/jahia-cloud/latest/About%20Jahia";
        assertThat(PageUrlRepair.needsRepair(alias, CANONICAL)).isTrue();
    }

    @Test
    public void aTrailingSlashDifferenceIsStillARewrite() {
        // Deliberate: the comparison is exact, so the stored value always converges on what indexation produces.
        assertThat(PageUrlRepair.needsRepair(CANONICAL + "/", CANONICAL)).isTrue();
    }
}
