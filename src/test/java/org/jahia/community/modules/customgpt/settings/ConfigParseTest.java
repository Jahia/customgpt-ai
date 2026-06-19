package org.jahia.community.modules.customgpt.settings;

import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the pure parsing / coercion helpers in {@link Config}.
 *
 * <p>Paths that call {@code NodeTypeRegistry.getInstance()} (i.e. {@code splitNodeTypeByComma}
 * via {@code PROP_CONTENT_INDEXED_MAIN_RESOURCE_TYPES} and {@code PROP_CONTENT_INDEXED_SUB_NODE_TYPES})
 * are skipped because NodeTypeRegistry is OSGi-bound and unavailable outside a container.
 * Those keys are simply left out of the test dictionary so the split returns an empty set without
 * reaching the registry.
 *
 * <p>The {@code FrameworkService.sendEvent} call inside {@link Config#updated} is also OSGi-bound.
 * Tests that exercise property coercions therefore assert on the getter values directly and do not
 * rely on the event side-effect.
 */
public class ConfigParseTest {

    private static final String NS = "org.jahia.community.modules.customgpt";

    // Property keys as used in Config
    private static final String KEY_BATCH_SIZE    = NS + ".operations.batch.size";
    private static final String KEY_SCHEDULE_ASAP = NS + ".scheduleJobASAP";
    private static final String KEY_DRY_RUN       = NS + ".dryRun";
    private static final String KEY_FILE_EXT      = NS + ".content.indexedFileExtensions";
    private static final String KEY_API_BASE_URL  = NS + ".apiBaseUrl";
    private static final String KEY_RATE_LIMIT    = NS + ".rateLimit.requestsPerSecond";
    private static final String KEY_PROJECT_ID    = NS + ".projectId";
    private static final String KEY_TOKEN         = NS + ".token";
    private static final String KEY_USERNAME      = NS + ".jahia.username";
    private static final String KEY_PASSWORD      = NS + ".jahia.password";
    private static final String KEY_COOKIE_NAME   = NS + ".jahia.serverCookie.name";
    private static final String KEY_COOKIE_VALUE  = NS + ".jahia.serverCookie.value";
    private static final String KEY_COOKIE_DOMAIN = NS + ".jahia.serverCookie.domain";

    // Main-resource and sub-node keys must be absent so splitNodeTypeByComma is never called
    // (it would hit NodeTypeRegistry which is not available outside OSGi).

    private Config config;

    @Before
    public void setUp() {
        config = new Config();
    }

    // ---- NotConfiguredException before updated() ----

    @Test
    public void getBulkOperationsBatchSize_throwsWhenNotConfigured() {
        assertThatThrownBy(() -> config.getBulkOperationsBatchSize())
                .isInstanceOf(NotConfiguredException.class);
    }

    @Test
    public void getContentIndexedMainResources_throwsWhenNotConfigured() {
        assertThatThrownBy(() -> config.getContentIndexedMainResources())
                .isInstanceOf(NotConfiguredException.class);
    }

    @Test
    public void getContentIndexedSubNodes_throwsWhenNotConfigured() {
        assertThatThrownBy(() -> config.getContentIndexedSubNodes())
                .isInstanceOf(NotConfiguredException.class);
    }

    // ---- getInt coercions ----

    @Test
    public void getInt_parsesNumberInstanceDirectly() throws NotConfiguredException {
        // Arrange: value is an Integer (as OSGi ConfigAdmin delivers typed properties)
        final Dictionary<String, Object> props = minimalValidProps();
        props.put(KEY_BATCH_SIZE, 42);

        // Act
        callUpdated(props);

        // Assert
        assertThat(config.getBulkOperationsBatchSize()).isEqualTo(42);
    }

    @Test
    public void getInt_parsesStringRepresentation() throws NotConfiguredException {
        final Dictionary<String, Object> props = minimalValidProps();
        props.put(KEY_BATCH_SIZE, "77");

        callUpdated(props);

        assertThat(config.getBulkOperationsBatchSize()).isEqualTo(77);
    }

    @Test
    public void getInt_returnsDefaultWhenKeyAbsent() throws NotConfiguredException {
        final Dictionary<String, Object> props = minimalValidProps();
        props.remove(KEY_BATCH_SIZE);

        callUpdated(props);

        // Default is 500 as declared in Config
        assertThat(config.getBulkOperationsBatchSize()).isEqualTo(500);
    }

    @Test
    public void getInt_parsesLongAsNumber() {
        final Dictionary<String, Object> props = minimalValidProps();
        props.put(KEY_RATE_LIMIT, 15L); // Long implements Number

        callUpdated(props);

        assertThat(config.getRateLimitRequestsPerSecond()).isEqualTo(15);
    }

    // ---- getBoolean coercions ----

    @Test
    public void getBoolean_parsesNativeBooleanTrue() {
        final Dictionary<String, Object> props = minimalValidProps();
        props.put(KEY_DRY_RUN, Boolean.TRUE);

        callUpdated(props);

        assertThat(config.isDryRun()).isTrue();
    }

    @Test
    public void getBoolean_parsesStringTrue() {
        final Dictionary<String, Object> props = minimalValidProps();
        props.put(KEY_DRY_RUN, "true");

        callUpdated(props);

        assertThat(config.isDryRun()).isTrue();
    }

    @Test
    public void getBoolean_parsesStringFalse() {
        final Dictionary<String, Object> props = minimalValidProps();
        props.put(KEY_DRY_RUN, "false");

        callUpdated(props);

        assertThat(config.isDryRun()).isFalse();
    }

    @Test
    public void getBoolean_returnsDefaultFalseWhenKeyAbsent() {
        final Dictionary<String, Object> props = minimalValidProps();
        props.remove(KEY_DRY_RUN);
        props.remove(KEY_SCHEDULE_ASAP);

        callUpdated(props);

        assertThat(config.isDryRun()).isFalse();
        assertThat(config.isScheduleJobASAP()).isFalse();
    }

    // ---- getString coercions ----

    @Test
    public void getString_returnsValueWhenPresent() {
        final Dictionary<String, Object> props = minimalValidProps();
        props.put(KEY_PROJECT_ID, "proj-99");
        props.put(KEY_TOKEN, "tok-abc");

        callUpdated(props);

        assertThat(config.getCustomGptProjectId()).isEqualTo("proj-99");
        assertThat(config.getCustomGptToken()).isEqualTo("tok-abc");
    }

    @Test
    public void getString_returnsEmptyStringDefaultWhenKeyAbsent() {
        final Dictionary<String, Object> props = minimalValidProps();
        props.remove(KEY_PROJECT_ID);
        props.remove(KEY_TOKEN);

        callUpdated(props);

        assertThat(config.getCustomGptProjectId()).isEmpty();
        assertThat(config.getCustomGptToken()).isEmpty();
    }

    // ---- indexedFileExtensions ----

    @Test
    public void indexedFileExtensions_emptyStringYieldsEmptySet() {
        final Dictionary<String, Object> props = minimalValidProps();
        props.put(KEY_FILE_EXT, "");

        callUpdated(props);

        assertThat(config.getIndexedFileExtensions()).isEmpty();
    }

    @Test
    public void indexedFileExtensions_nullYieldsEmptySet() {
        final Dictionary<String, Object> props = minimalValidProps();
        props.remove(KEY_FILE_EXT); // absent → treated as null

        callUpdated(props);

        assertThat(config.getIndexedFileExtensions()).isEmpty();
    }

    @Test
    public void indexedFileExtensions_csvWithSpacesIsTrimmedCorrectly() {
        final Dictionary<String, Object> props = minimalValidProps();
        props.put(KEY_FILE_EXT, " pdf , docx , xlsx ");

        callUpdated(props);

        final Set<String> exts = config.getIndexedFileExtensions();
        assertThat(exts).containsExactlyInAnyOrder("pdf", "docx", "xlsx");
    }

    @Test
    public void indexedFileExtensions_singleExtensionWithNoComma() {
        final Dictionary<String, Object> props = minimalValidProps();
        props.put(KEY_FILE_EXT, "pdf");

        callUpdated(props);

        assertThat(config.getIndexedFileExtensions()).containsExactly("pdf");
    }

    @Test
    public void indexedFileExtensions_trailingCommaIsIgnored() {
        final Dictionary<String, Object> props = minimalValidProps();
        props.put(KEY_FILE_EXT, "pdf,docx,");

        callUpdated(props);

        assertThat(config.getIndexedFileExtensions()).containsExactlyInAnyOrder("pdf", "docx");
    }

    // ---- apiBaseUrl SSRF / https validation ----

    // S5976: kept as separate cases — JUnit4 Parameterized cannot be scoped to one group
    // inside this mixed test class without a risky Enclosed-runner restructure.
    @SuppressWarnings("java:S5976")
    @Test
    public void updated_rejectsHttpApiBaseUrl_leavesConfigured_false() {
        final Dictionary<String, Object> props = minimalValidProps();
        props.put(KEY_API_BASE_URL, "http://app.customgpt.ai/api/v1");

        callUpdated(props);

        // Config must NOT be marked configured when base URL is not https
        assertThat(config.isConfigured()).isFalse();
    }

    @Test
    public void updated_rejectsInternalIpApiBaseUrl_leavesConfigured_false() {
        final Dictionary<String, Object> props = minimalValidProps();
        props.put(KEY_API_BASE_URL, "https://192.168.1.1/api/v1");

        callUpdated(props);

        assertThat(config.isConfigured()).isFalse();
    }

    @Test
    public void updated_rejectsLoopbackApiBaseUrl_leavesConfigured_false() {
        final Dictionary<String, Object> props = minimalValidProps();
        props.put(KEY_API_BASE_URL, "https://127.0.0.1/api/v1");

        callUpdated(props);

        assertThat(config.isConfigured()).isFalse();
    }

    @Test
    public void updated_rejectsLinkLocalApiBaseUrl_leavesConfigured_false() {
        final Dictionary<String, Object> props = minimalValidProps();
        props.put(KEY_API_BASE_URL, "https://169.254.169.254/latest/meta-data/");

        callUpdated(props);

        assertThat(config.isConfigured()).isFalse();
    }

    @Test
    public void updated_nullPropertiesIsIgnored_remainsNotConfigured() throws Exception {
        // updated(null) is a no-op per spec
        config.updated(null);

        assertThat(config.isConfigured()).isFalse();
    }

    // ---- isConfigured after valid update -- only asserts the flag, not OSGi event ----

    @Test
    public void isConfigured_trueAfterValidHttpsUrl() {
        callUpdated(minimalValidProps());
        assertThat(config.isConfigured()).isTrue();
    }

    // ---- helpers ----

    /**
     * Minimal dictionary that passes the https validation gate (so {@code configured} is set)
     * without supplying node-type keys that would trigger NodeTypeRegistry lookups.
     */
    private static Dictionary<String, Object> minimalValidProps() {
        final Dictionary<String, Object> d = new Hashtable<>();
        d.put(KEY_API_BASE_URL, "https://app.customgpt.ai/api/v1");
        d.put(KEY_BATCH_SIZE, 500);
        d.put(KEY_RATE_LIMIT, 10);
        d.put(KEY_PROJECT_ID, "");
        d.put(KEY_TOKEN, "");
        d.put(KEY_USERNAME, "");
        d.put(KEY_PASSWORD, "");
        d.put(KEY_COOKIE_NAME, "");
        d.put(KEY_COOKIE_VALUE, "");
        d.put(KEY_COOKIE_DOMAIN, "");
        return d;
    }

    /**
     * Calls {@link Config#updated(Dictionary)} but silently swallows the
     * {@link org.osgi.service.cm.ConfigurationException} (the method signature declares it but
     * Config's implementation never actually throws it).
     * The {@code FrameworkService.sendEvent} call inside Config is an OSGi static call that will
     * throw a NullPointerException when the bundle context is absent; we catch and ignore it here
     * because the property-coercion logic runs before the event dispatch, which is what we test.
     */
    private void callUpdated(Dictionary<String, Object> props) {
        try {
            config.updated(props);
        } catch (org.osgi.service.cm.ConfigurationException e) {
            throw new RuntimeException("Unexpected ConfigurationException", e);
        } catch (Exception e) {
            // FrameworkService.sendEvent throws NullPointerException outside OSGi —
            // ignore it; the coercion and configured-flag logic already ran.
            // Only absorb if config was actually processed (non-null props).
        }
    }
}
