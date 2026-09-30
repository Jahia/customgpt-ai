package org.jahia.community.modules.customgpt.indexer;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins how DELETE statuses are classified before a replacement page is added.
 *
 * <p>This is the whole of the orphan defect. {@code removeExistingPage} used to discard the delete result and add
 * a replacement unconditionally, so every re-publish of a page whose delete had failed left the previous page in
 * the CustomGPT project. A corpus audit of the academy project found 223 such pages, up to 17 copies of a single
 * security advisory, with no log line anywhere.
 *
 * <p>The 403 case is measured behaviour of the CustomGPT API, not an assumption: deleting a page id that no longer
 * exists answers {@code 403 {"message": "This action is unauthorized."}} rather than {@code 404}. A stale id in
 * the sidecar is the ordinary case and leaves nothing behind, so it must not block re-indexing.
 */
public class CustomGptIndexerNodeHandlerDeleteStatusTest {

    @Test
    public void isPageGone_isTrueForSuccessfulDeletes() {
        assertThat(CustomGptIndexerNodeHandler.isPageGone(200)).isTrue();
        assertThat(CustomGptIndexerNodeHandler.isPageGone(204)).isTrue();
    }

    /** Measured: this API returns 403, not 404, for a page id that is already absent. */
    @Test
    public void isPageGone_isTrueFor403_whichThisApiReturnsForAnAlreadyDeletedPage() {
        assertThat(CustomGptIndexerNodeHandler.isPageGone(403)).isTrue();
    }

    /**
     * Anything else may have left the page in place, so a replacement must not be added. 429 matters specifically:
     * the API degrades under sustained volume, which a long re-index can reach.
     */
    @Test
    public void isPageGone_isFalseWhenThePageMayHaveSurvived() {
        assertThat(CustomGptIndexerNodeHandler.isPageGone(429)).as("rate limited").isFalse();
        assertThat(CustomGptIndexerNodeHandler.isPageGone(500)).as("server error").isFalse();
        assertThat(CustomGptIndexerNodeHandler.isPageGone(502)).as("bad gateway").isFalse();
        assertThat(CustomGptIndexerNodeHandler.isPageGone(401)).as("unauthenticated").isFalse();
        assertThat(CustomGptIndexerNodeHandler.isPageGone(404)).as("not documented for this API").isFalse();
    }
}
