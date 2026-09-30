package org.jahia.community.modules.customgpt.indexer;

import org.junit.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards on a computed URL, before it is written over a good one.
 *
 * <p>These exist because of a production incident. {@code PageUrlRepair} opened its JCR session with a null
 * locale, {@code JCRNodeWrapper.getUrl()} rendered that locale into the path as the literal string "null", and
 * the repair wrote {@code https://academy.jahia.com/null/<jcr-path>.html} over URLs that were correct. Every
 * page it touched got a 404.
 *
 * <p>The read-back verification could not catch it and never could: it proves the value was STORED, not that it
 * is RIGHT. Writing a corrupt URL and reading the same corrupt URL back passes the {@code data.id} and
 * {@code data.url} checks perfectly. Correctness has to be judged before the write.
 *
 * <p>The host comparison is the strongest of these. A computed URL that disagrees with the stored one on the
 * host is far more likely to be a bug than a legitimate move.
 */
public class PageUrlGuardTest {

    private static final String GOOD = "https://academy.jahia.com/contents/knowledge-base/2025/deploy-on-site";
    private static final String CORRUPT =
            "https://academy.jahia.com/null/contents/knowledge-base/2025/deploy-on-site.html";

    @Test
    public void aNullPathSegmentIsRefused() {
        // The exact production shape.
        assertThatThrownBy(() -> PageUrlRepair.assertUrlUsable(CORRUPT, GOOD))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("null");
    }

    @Test
    public void aNullSegmentIsRefusedEvenWithNoStoredUrlToCompareAgainst() {
        // The 47 null-URL pages have nothing to compare a host against, so this check has to stand alone.
        assertThatThrownBy(() -> PageUrlRepair.assertUrlUsable(CORRUPT, null))
                .isInstanceOf(IOException.class);
    }

    @Test
    public void aTrailingNullSegmentIsRefused() {
        assertThatThrownBy(() -> PageUrlRepair.assertUrlUsable("https://academy.jahia.com/contents/null", GOOD))
                .isInstanceOf(IOException.class);
    }

    @Test
    public void aWordMerelyContainingNullIsAccepted() {
        // A page about null values is legitimate; only a whole segment equal to "null" is the marker.
        assertThatCode(() -> PageUrlRepair.assertUrlUsable(
                "https://academy.jahia.com/kb/what-is-a-null-value", GOOD)).doesNotThrowAnyException();
        assertThatCode(() -> PageUrlRepair.assertUrlUsable(
                "https://academy.jahia.com/kb/nullable-fields", GOOD)).doesNotThrowAnyException();
    }

    @Test
    public void aComputedUrlOnADifferentHostIsRefused() {
        assertThatThrownBy(() -> PageUrlRepair.assertUrlUsable("https://www.jahia.com/contents/x", GOOD))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("host");
    }

    @Test
    public void aMovedPathOnTheSameHostIsAccepted() {
        // The legitimate case this must not block: the 16 /jahia-cloud/latest/ pages moving to their canonical path.
        assertThatCode(() -> PageUrlRepair.assertUrlUsable(
                "https://academy.jahia.com/documentation/jahia-cloud/jahia-cloud/about-jahia",
                "https://academy.jahia.com/home/documentation/jahia-cloud/latest/About%20Jahia"))
                .doesNotThrowAnyException();
    }

    @Test
    public void anUnparseableUrlIsRefused() {
        assertThatThrownBy(() -> PageUrlRepair.assertUrlUsable("not a url", GOOD)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> PageUrlRepair.assertUrlUsable("", GOOD)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> PageUrlRepair.assertUrlUsable(null, GOOD)).isInstanceOf(IOException.class);
    }

    @Test
    public void aGoodUrlWithNoStoredCounterpartIsAccepted() {
        assertThatCode(() -> PageUrlRepair.assertUrlUsable(GOOD, null)).doesNotThrowAnyException();
    }

    // --- blast radius --------------------------------------------------------
    //
    // The per-URL guard catches a computation that produces an obviously broken URL. This catches the other
    // shape: one that produces a plausible but wrong URL for everything, which no per-URL check can tell apart
    // from a genuine mass move.

    @Test
    public void rewritingTheWholeCorpusIsRefused() {
        // What the incident would have done: every page differs from a systematically wrong computed value.
        assertThatThrownBy(() -> PageUrlRepair.assertPlanIsPlausible(1871, 1920))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("dryRun");
    }

    @Test
    public void theExpectedRealWorldScopeIsAllowed() {
        // 47 null URLs + 16 alias paths + up to ~16 normalisations, against ~1920 examined pages.
        assertThatCode(() -> PageUrlRepair.assertPlanIsPlausible(79, 1920)).doesNotThrowAnyException();
    }

    @Test
    public void rewritingNothingIsAllowed() {
        assertThatCode(() -> PageUrlRepair.assertPlanIsPlausible(0, 1920)).doesNotThrowAnyException();
    }

    @Test
    public void anEmptyExaminationIsNotTreatedAsAViolation() {
        assertThatCode(() -> PageUrlRepair.assertPlanIsPlausible(0, 0)).doesNotThrowAnyException();
    }

    // --- run size ------------------------------------------------------------
    //
    // This runs synchronously on the caller's request thread. A whole-site run over thousands of mapping nodes
    // takes ~30 minutes and no client survives it — and the client giving up does NOT stop the server, so a real
    // run would rewrite an unknown subset with no summary and no returned count.

    @Test
    public void aWholeSiteRunIsRefusedRatherThanStartedAndAbandoned() {
        assertThatThrownBy(() -> PageUrlRepair.assertRunIsBounded(2784))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("pageIds");
    }

    @Test
    public void aScopedRunIsAllowed() {
        // The 58 confirmed targets, scoped by page id, finish inside a request comfortably.
        assertThatCode(() -> PageUrlRepair.assertRunIsBounded(58)).doesNotThrowAnyException();
    }

    @Test
    public void aRunAtExactlyTheLimitIsAllowed() {
        assertThatCode(() -> PageUrlRepair.assertRunIsBounded(100)).doesNotThrowAnyException();
        assertThatThrownBy(() -> PageUrlRepair.assertRunIsBounded(101)).isInstanceOf(IOException.class);
    }
}
