package org.jahia.community.modules.customgpt.settings;

import java.util.Dictionary;
import java.util.Hashtable;
import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the indexation server-name override parsed by {@link Config}.
 *
 * <p>The override answers "index this site under this host instead of the one in its {@code sitemapIndexURL}" —
 * needed when a site node carries the production sitemap URL on a preproduction instance, or carries none at all.
 * Resolution is per-site first, then a global default, then (returning empty here) the site's own sitemapIndexURL.
 *
 * <p>Same OSGi caveats as {@link ConfigParseTest}: node-type keys are left out so {@code NodeTypeRegistry} is never
 * reached, and the {@code FrameworkService.sendEvent} at the end of {@code updated} is absorbed by {@code callUpdated}.
 */
public class ConfigServerNameTest {

    private static final String NS = "org.jahia.community.modules.customgpt";
    private static final String KEY_API_BASE_URL = NS + ".apiBaseUrl";
    private static final String KEY_SERVER_NAME = NS + ".serverName";
    private static final String KEY_SERVER_NAME_ACADEMY = NS + ".site.academy.serverName";
    private static final String KEY_SERVER_NAME_DIGITALL = NS + ".site.digitall.serverName";

    private Config config;

    @Before
    public void setUp() {
        config = new Config();
    }

    // ---- nothing configured ----

    @Test
    public void getServerName_isEmptyBeforeAnyUpdate() {
        // No update() yet: callers must fall back to the site's sitemapIndexURL rather than see a null.
        assertThat(config.getServerName("academy")).isEmpty();
    }

    @Test
    public void getServerName_isEmptyWhenNoOverrideIsConfigured() {
        callUpdated(minimalValidProps());

        assertThat(config.getServerName("academy")).isEmpty();
    }

    // ---- global default ----

    @Test
    public void getServerName_appliesTheGlobalOverrideToAnySite() {
        // Arrange: one override for the whole instance, the usual preproduction case
        final Dictionary<String, Object> props = minimalValidProps();
        props.put(KEY_SERVER_NAME, "https://academypp.jahia.com");

        // Act
        callUpdated(props);

        // Assert
        assertThat(config.getServerName("academy")).isEqualTo("https://academypp.jahia.com");
        assertThat(config.getServerName("digitall")).isEqualTo("https://academypp.jahia.com");
    }

    @Test
    public void getServerName_normalisesTheConfiguredValue() {
        final Dictionary<String, Object> props = minimalValidProps();
        props.put(KEY_SERVER_NAME, "https://academypp.jahia.com/sitemap.xml");

        callUpdated(props);

        assertThat(config.getServerName("academy")).isEqualTo("https://academypp.jahia.com");
    }

    // ---- per-site override ----

    @Test
    public void getServerName_prefersThePerSiteOverrideOverTheGlobalOne() {
        // Arrange: "in some cases" — one site keeps its own host while the rest follow the global default
        final Dictionary<String, Object> props = minimalValidProps();
        props.put(KEY_SERVER_NAME, "https://academypp.jahia.com");
        props.put(KEY_SERVER_NAME_ACADEMY, "https://academy.jahia.com");

        callUpdated(props);

        assertThat(config.getServerName("academy")).isEqualTo("https://academy.jahia.com");
        assertThat(config.getServerName("digitall")).isEqualTo("https://academypp.jahia.com");
    }

    @Test
    public void getServerName_doesNotLeakOneSitesOverrideToAnother() {
        final Dictionary<String, Object> props = minimalValidProps();
        props.put(KEY_SERVER_NAME_ACADEMY, "https://academy.jahia.com");

        callUpdated(props);

        assertThat(config.getServerName("academy")).isEqualTo("https://academy.jahia.com");
        assertThat(config.getServerName("digitall")).isEmpty();
    }

    @Test
    public void getServerName_readsSeveralPerSiteOverrides() {
        final Dictionary<String, Object> props = minimalValidProps();
        props.put(KEY_SERVER_NAME_ACADEMY, "https://academy.jahia.com");
        props.put(KEY_SERVER_NAME_DIGITALL, "https://digitall.example.org");

        callUpdated(props);

        assertThat(config.getServerName("academy")).isEqualTo("https://academy.jahia.com");
        assertThat(config.getServerName("digitall")).isEqualTo("https://digitall.example.org");
    }

    @Test
    public void getServerName_fallsBackToTheGlobalOverrideWhenTheSiteKeyIsUnknown() {
        final Dictionary<String, Object> props = minimalValidProps();
        props.put(KEY_SERVER_NAME, "https://academypp.jahia.com");

        callUpdated(props);

        assertThat(config.getServerName(null)).isEqualTo("https://academypp.jahia.com");
        assertThat(config.getServerName("")).isEqualTo("https://academypp.jahia.com");
    }

    // ---- rejected values fall back rather than break indexation ----

    @Test
    public void getServerName_ignoresAMalformedGlobalOverride() {
        // A bad value must not become a host: it would be concatenated into every indexed URL. Falling back to
        // sitemapIndexURL keeps indexation working, and Config logs the rejection.
        final Dictionary<String, Object> props = minimalValidProps();
        props.put(KEY_SERVER_NAME, "academypp.jahia.com");

        callUpdated(props);

        assertThat(config.getServerName("academy")).isEmpty();
    }

    @Test
    public void getServerName_ignoresAnInternalPerSiteOverride() {
        final Dictionary<String, Object> props = minimalValidProps();
        props.put(KEY_SERVER_NAME_ACADEMY, "https://127.0.0.1:8080");

        callUpdated(props);

        assertThat(config.getServerName("academy")).isEmpty();
    }

    @Test
    public void getServerName_fallsBackToTheGlobalOverrideWhenThePerSiteOneIsRejected() {
        final Dictionary<String, Object> props = minimalValidProps();
        props.put(KEY_SERVER_NAME, "https://academypp.jahia.com");
        props.put(KEY_SERVER_NAME_ACADEMY, "ftp://academy.jahia.com");

        callUpdated(props);

        assertThat(config.getServerName("academy")).isEqualTo("https://academypp.jahia.com");
    }

    @Test
    public void getServerName_ignoresAnEmptyPerSiteOverride() {
        // The shipped .cfg can carry the key with no value; that means "not configured", not "no host".
        final Dictionary<String, Object> props = minimalValidProps();
        props.put(KEY_SERVER_NAME, "https://academypp.jahia.com");
        props.put(KEY_SERVER_NAME_ACADEMY, "");

        callUpdated(props);

        assertThat(config.getServerName("academy")).isEqualTo("https://academypp.jahia.com");
    }

    @Test
    public void getServerName_ignoresAKeyWhoseSiteSegmentIsNotASafeSiteKey() {
        // The site key is matched against JCR site keys; a path-shaped segment is a typo, never a site.
        final Dictionary<String, Object> props = minimalValidProps();
        props.put(NS + ".site.academy/home.serverName", "https://evil.example.org");

        callUpdated(props);

        assertThat(config.getServerName("academy/home")).isEmpty();
        assertThat(config.getServerName("academy")).isEmpty();
    }

    @Test
    public void getServerName_ignoresAnUnrelatedSiteScopedProperty() {
        final Dictionary<String, Object> props = minimalValidProps();
        props.put(NS + ".site.academy.somethingElse", "https://evil.example.org");

        callUpdated(props);

        assertThat(config.getServerName("academy")).isEmpty();
    }

    @Test
    public void getServerName_readsASiteKeyWhoseKeyWasTypedWithDifferentCasing() {
        // ConfigAdmin hands over a case-insensitive dictionary, so a .cfg written as ".Site.academy.ServerName"
        // is a readable property. A case-sensitive scan would drop it without a word.
        final Dictionary<String, Object> props = minimalValidProps();
        props.put(NS + ".Site.academy.ServerName", "https://academy.jahia.com");

        callUpdated(props);

        assertThat(config.getServerName("academy")).isEqualTo("https://academy.jahia.com");
    }

    @Test
    public void getServerName_keepsTheSiteKeySegmentCaseSensitive() {
        // JCR site keys are case-sensitive, so only the surrounding property name is matched loosely.
        final Dictionary<String, Object> props = minimalValidProps();
        props.put(NS + ".site.Academy.serverName", "https://academy.jahia.com");

        callUpdated(props);

        assertThat(config.getServerName("Academy")).isEqualTo("https://academy.jahia.com");
        assertThat(config.getServerName("academy")).isEmpty();
    }

    // ---- re-reading the configuration replaces the previous overrides ----

    @Test
    public void getServerName_forgetsAnOverrideRemovedFromTheConfiguration() {
        final Dictionary<String, Object> first = minimalValidProps();
        first.put(KEY_SERVER_NAME_ACADEMY, "https://academy.jahia.com");
        callUpdated(first);

        callUpdated(minimalValidProps());

        assertThat(config.getServerName("academy")).isEmpty();
    }

    // ---- helpers ----

    private static Dictionary<String, Object> minimalValidProps() {
        final Dictionary<String, Object> d = new Hashtable<>();
        d.put(KEY_API_BASE_URL, "https://app.customgpt.ai/api/v1");
        return d;
    }

    private void callUpdated(Dictionary<String, Object> props) {
        try {
            config.updated(props);
        } catch (Exception e) {
            // FrameworkService.sendEvent throws outside an OSGi container; the parsing under test already ran.
        }
    }
}
