package org.jahia.community.modules.customgpt.indexer;

import org.json.JSONObject;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the predicate that selects pages to repair.
 *
 * <p>The shape is measured, not assumed: all 47 affected pages in the production corpus return the identical
 * five-key set {@code [description, id, image, title, url]} with {@code url} set to JSON null. The key is never
 * absent and never empty, and {@code description}/{@code image} are null on healthy pages too - so {@code url} is
 * the only field that distinguishes a dropped write.
 */
public class PageUrlRepairTest {

    private static JSONObject metadata(String rawUrlJson) {
        return new JSONObject("{\"id\":85803619,\"title\":\"t\",\"description\":null,\"image\":null,"
                + "\"url\":" + rawUrlJson + "}");
    }

    @Test
    public void aPageWithAUrlIsLeftAlone() {
        assertThat(PageUrlRepair.hasStoredUrl(metadata("\"https://academy.example/kb/x\""))).isTrue();
    }

    @Test
    public void aPageWhoseUrlIsJsonNullIsRepaired() {
        // The exact production shape of all 47.
        assertThat(PageUrlRepair.hasStoredUrl(metadata("null"))).isFalse();
    }

    @Test
    public void aPageWithNoUrlKeyAtAllIsRepaired() {
        assertThat(PageUrlRepair.hasStoredUrl(new JSONObject("{\"id\":1,\"title\":\"t\"}"))).isFalse();
    }

    @Test
    public void aPageWithABlankUrlIsRepaired() {
        assertThat(PageUrlRepair.hasStoredUrl(metadata("\"\""))).isFalse();
        assertThat(PageUrlRepair.hasStoredUrl(metadata("\"   \""))).isFalse();
    }

    @Test
    public void anAbsentDataObjectIsTreatedAsNeedingRepair() {
        assertThat(PageUrlRepair.hasStoredUrl(null)).isFalse();
    }

    @Test
    public void nullDescriptionAndImageDoNotMakeAHealthyPageLookBroken() {
        // description and image are null on healthy pages too; only url may drive the decision.
        assertThat(PageUrlRepair.hasStoredUrl(metadata("\"https://academy.example/x\""))).isTrue();
    }
}
