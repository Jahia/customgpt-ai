package org.jahia.community.modules.customgpt.graphql.extensions.models;

import java.util.Dictionary;
import java.util.Hashtable;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for how the settings panel's per-site server names are written into the OSGi configuration.
 *
 * <p>The panel always submits the complete list, so this REPLACES every {@code .site.<siteKey>.serverName}
 * entry rather than merging. Merging would make a row impossible to delete through the UI: the entry would
 * survive every save and keep retargeting a site that no longer appears in the list.
 *
 * <p>JUnit 4: the jahia-modules parent pins the {@code surefire-junit4} provider.
 */
public class SiteServerNamesSaveTest {

    private static final String NS = "org.jahia.community.modules.customgpt";
    private static final String ACADEMY = NS + ".site.academy.serverName";
    private static final String DIGITALL = NS + ".site.digitall.serverName";

    private static Dictionary<String, Object> propsWith(String... keyValues) {
        final Dictionary<String, Object> props = new Hashtable<>();
        props.put(NS + ".projectId", "123");
        for (int i = 0; i < keyValues.length; i += 2) {
            props.put(keyValues[i], keyValues[i + 1]);
        }
        return props;
    }

    @Test
    public void applySiteServerNames_writesOneEntryPerLine() {
        final Dictionary<String, Object> props = propsWith();

        GqlCustomGptAdminMutationResult.applySiteServerNames(props,
                "academy=https://academy.jahia.com\ndigitall=digitall.example.com");

        assertThat(props.get(ACADEMY)).isEqualTo("https://academy.jahia.com");
        assertThat(props.get(DIGITALL)).isEqualTo("digitall.example.com");
    }

    @Test
    public void applySiteServerNames_removesAnEntryTheAdminDeleted() {
        final Dictionary<String, Object> props = propsWith(ACADEMY, "https://academy.jahia.com",
                DIGITALL, "digitall.example.com");

        GqlCustomGptAdminMutationResult.applySiteServerNames(props, "academy=https://academy.jahia.com");

        assertThat(props.get(ACADEMY)).isEqualTo("https://academy.jahia.com");
        assertThat(props.get(DIGITALL)).isNull();
    }

    @Test
    public void applySiteServerNames_clearsEveryEntryWhenTheListIsEmptied() {
        final Dictionary<String, Object> props = propsWith(ACADEMY, "https://academy.jahia.com");

        GqlCustomGptAdminMutationResult.applySiteServerNames(props, "");

        assertThat(props.get(ACADEMY)).isNull();
        // Only the site-scoped keys are touched.
        assertThat(props.get(NS + ".projectId")).isEqualTo("123");
    }

    @Test
    public void applySiteServerNames_leavesEverythingAloneWhenNotSubmitted() {
        // null means "this client did not send the field" - the Cypress harness saves a partial set of
        // settings, and an older client would too. Wiping the overrides then would be data loss.
        final Dictionary<String, Object> props = propsWith(ACADEMY, "https://academy.jahia.com");

        GqlCustomGptAdminMutationResult.applySiteServerNames(props, null);

        assertThat(props.get(ACADEMY)).isEqualTo("https://academy.jahia.com");
    }

    @Test
    public void applySiteServerNames_removesADifferentlyCasedStaleEntry() {
        // ConfigAdmin's dictionary is case-insensitive, so a key stored with other casing is the same property
        // and must still be cleared - otherwise a deleted row quietly stays in effect.
        final Dictionary<String, Object> props = propsWith(NS + ".Site.Academy.ServerName", "https://old.example.com");

        GqlCustomGptAdminMutationResult.applySiteServerNames(props, "");

        assertThat(props.get(NS + ".Site.Academy.ServerName")).isNull();
    }

    @Test
    public void applySiteServerNames_keepsAnEqualsSignInsideTheValue() {
        final Dictionary<String, Object> props = propsWith();

        GqlCustomGptAdminMutationResult.applySiteServerNames(props, "academy=https://host/p?a=b");

        assertThat(props.get(ACADEMY)).isEqualTo("https://host/p?a=b");
    }

    @Test
    public void applySiteServerNames_skipsBlankAndMalformedLines() {
        final Dictionary<String, Object> props = propsWith();

        GqlCustomGptAdminMutationResult.applySiteServerNames(props,
                "\n   \nacademy=https://academy.jahia.com\nnoEqualsSign\n=noSiteKey\ndigitall=\n");

        assertThat(props.get(ACADEMY)).isEqualTo("https://academy.jahia.com");
        assertThat(props.get(DIGITALL)).isNull();
        assertThat(props.get(NS + ".site.noEqualsSign.serverName")).isNull();
    }

    @Test
    public void applySiteServerNames_rejectsASiteKeyThatIsNotASingleSafeSegment() {
        // The site key becomes part of a property name; a path separator or a dot would address another key.
        final Dictionary<String, Object> props = propsWith();

        GqlCustomGptAdminMutationResult.applySiteServerNames(props,
                "../../etc=https://evil.example.com\na.b=https://evil.example.com");

        assertThat(props.get(NS + ".site.../../etc.serverName")).isNull();
        assertThat(props.get(NS + ".site.a.b.serverName")).isNull();
    }
}
