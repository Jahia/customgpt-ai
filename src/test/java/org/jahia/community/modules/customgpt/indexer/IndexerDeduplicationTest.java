package org.jahia.community.modules.customgpt.indexer;

import java.util.Collection;
import org.jahia.community.modules.customgpt.service.Service;
import org.jahia.community.modules.customgpt.settings.Config;
import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for the path deduplication logic in {@link Indexer#addNodeToDelete} and
 * {@link Indexer#isMarkedForRemoval}.
 *
 * No JCR, OSGi, or network dependencies are exercised.  The {@link Service} and {@link Config}
 * dependencies are mocked; only the in-memory {@code TreeSet<String>} deduplication logic is
 * exercised, which is purely deterministic.
 */
public class IndexerDeduplicationTest {

    private Indexer indexer;

    @Before
    public void setUp() {
        // Construct Indexer the same way production does: Service + Config mocks.
        // init() is deliberately NOT called because it resolves JahiaUserManagerService (OSGi).
        indexer = new Indexer(mock(Service.class), mock(Config.class));
    }

    // ---- isEmpty ----

    @Test
    public void isEmpty_trueWhenNothingQueued() {
        assertThat(indexer.isEmpty()).isTrue();
    }

    @Test
    public void isEmpty_falseAfterAddNodeToDelete() {
        indexer.addNodeToDelete("/sites/acme/home");
        assertThat(indexer.isEmpty()).isFalse();
    }

    // ---- blank / null path ignored ----

    // S5976: kept as separate cases — JUnit4 Parameterized cannot be scoped to one group
    // inside this mixed test class without a risky Enclosed-runner restructure.
    @SuppressWarnings("java:S5976")
    @Test
    public void addNodeToDelete_blankPathIsIgnored() {
        indexer.addNodeToDelete("   ");
        assertThat(indexer.isEmpty()).isTrue();
    }

    @Test
    public void addNodeToDelete_emptyPathIsIgnored() {
        indexer.addNodeToDelete("");
        assertThat(indexer.isEmpty()).isTrue();
    }

    @Test
    public void addNodeToDelete_nullPathIsIgnored() {
        indexer.addNodeToDelete(null);
        assertThat(indexer.isEmpty()).isTrue();
    }

    // ---- ancestor wins: descendant dropped ----

    @Test
    public void addNodeToDelete_ancestorAddedFirst_descendantIsDropped() {
        // Arrange
        final String ancestor = "/sites/acme/home";
        final String descendant = "/sites/acme/home/about";

        // Act
        indexer.addNodeToDelete(ancestor);
        indexer.addNodeToDelete(descendant);

        // Assert: only the ancestor is retained
        final Collection<String> toRemove = indexer.getNodePathsToRemove();
        assertThat(toRemove).containsExactly(ancestor);
    }

    @Test
    public void addNodeToDelete_deepDescendant_ancestorAlreadyPresent_dropped() {
        final String ancestor = "/sites/acme";
        indexer.addNodeToDelete(ancestor);
        indexer.addNodeToDelete("/sites/acme/home/about/team");

        assertThat(indexer.getNodePathsToRemove()).containsExactly(ancestor);
    }

    // ---- ancestor wins: existing descendants replaced ----

    @Test
    public void addNodeToDelete_ancestorAddedLast_existingDescendantsPruned() {
        // Arrange: add two descendants first
        final String d1 = "/sites/acme/home/about";
        final String d2 = "/sites/acme/home/contact";
        final String ancestor = "/sites/acme/home";

        indexer.addNodeToDelete(d1);
        indexer.addNodeToDelete(d2);

        // Act: add the ancestor — both descendants should be removed
        indexer.addNodeToDelete(ancestor);

        // Assert
        assertThat(indexer.getNodePathsToRemove()).containsExactly(ancestor);
    }

    @Test
    public void addNodeToDelete_multipleDescendantsPrunedByAncestor() {
        // Arrange: three descendants at different depths
        indexer.addNodeToDelete("/sites/acme/home/a");
        indexer.addNodeToDelete("/sites/acme/home/a/b");
        indexer.addNodeToDelete("/sites/acme/home/c");

        // Act
        indexer.addNodeToDelete("/sites/acme/home");

        // Assert: only the ancestor
        assertThat(indexer.getNodePathsToRemove()).containsExactly("/sites/acme/home");
    }

    // ---- sibling paths are both kept ----

    @Test
    public void addNodeToDelete_siblingsAreKeptSeparately() {
        final String p1 = "/sites/acme/home";
        final String p2 = "/sites/acme/news";

        indexer.addNodeToDelete(p1);
        indexer.addNodeToDelete(p2);

        assertThat(indexer.getNodePathsToRemove()).containsExactlyInAnyOrder(p1, p2);
    }

    // ---- prefix collision guard: path that is a string-prefix but not an ancestor ----

    @Test
    public void addNodeToDelete_stringPrefixButNotAncestor_bothKept() {
        // "/sites/acmefoo" starts with "/sites/acme" but is NOT a descendant of "/sites/acme"
        // because the delimiter "/" is absent immediately after "acme".
        final String p1 = "/sites/acme";
        final String p2 = "/sites/acmefoo";

        indexer.addNodeToDelete(p1);
        indexer.addNodeToDelete(p2);

        assertThat(indexer.getNodePathsToRemove()).containsExactlyInAnyOrder(p1, p2);
    }

    // ---- idempotent: same path added twice ----

    @Test
    public void addNodeToDelete_samePathTwiceResultsInSingleEntry() {
        indexer.addNodeToDelete("/sites/acme/home");
        indexer.addNodeToDelete("/sites/acme/home");

        assertThat(indexer.getNodePathsToRemove()).hasSize(1);
    }

    // ---- customGptPageId tracking ----

    @Test
    public void addNodeToDelete_withCustomGptPageId_tracksPageId() {
        indexer.addNodeToDelete("page-42", "/sites/acme/home");

        assertThat(indexer.getCustomGptPageToRemove()).containsExactly("page-42");
        assertThat(indexer.getNodePathsToRemove()).containsExactly("/sites/acme/home");
    }

    @Test
    public void addNodeToDelete_nullPageId_onlyPathTracked() {
        indexer.addNodeToDelete(null, "/sites/acme/home");

        assertThat(indexer.getCustomGptPageToRemove()).isEmpty();
        assertThat(indexer.getNodePathsToRemove()).containsExactly("/sites/acme/home");
    }

    // ---- isMarkedForRemoval ----

    @Test
    public void isMarkedForRemoval_exactMatch_returnsTrue() {
        indexer.addNodeToDelete("/sites/acme/home");

        assertThat(indexer.isMarkedForRemoval("/sites/acme/home")).isTrue();
    }

    @Test
    public void isMarkedForRemoval_descendantOfMarkedPath_returnsTrue() {
        indexer.addNodeToDelete("/sites/acme/home");

        assertThat(indexer.isMarkedForRemoval("/sites/acme/home/about")).isTrue();
        assertThat(indexer.isMarkedForRemoval("/sites/acme/home/about/team")).isTrue();
    }

    @Test
    public void isMarkedForRemoval_ancestorOfMarkedPath_returnsFalse() {
        indexer.addNodeToDelete("/sites/acme/home");

        assertThat(indexer.isMarkedForRemoval("/sites/acme")).isFalse();
        assertThat(indexer.isMarkedForRemoval("/sites")).isFalse();
    }

    @Test
    public void isMarkedForRemoval_unrelatedPath_returnsFalse() {
        indexer.addNodeToDelete("/sites/acme/home");

        assertThat(indexer.isMarkedForRemoval("/sites/other/page")).isFalse();
    }

    @Test
    public void isMarkedForRemoval_stringPrefixButNotDescendant_returnsFalse() {
        // "/sites/acme" is marked; "/sites/acmefoo" must NOT be considered a descendant
        indexer.addNodeToDelete("/sites/acme");

        assertThat(indexer.isMarkedForRemoval("/sites/acmefoo")).isFalse();
    }

    @Test
    public void isMarkedForRemoval_emptySet_returnsFalse() {
        assertThat(indexer.isMarkedForRemoval("/sites/acme/home")).isFalse();
    }
}
