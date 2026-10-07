package org.jahia.community.modules.customgpt.settings;

import java.util.Dictionary;
import java.util.Hashtable;
import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the configurable rendering User-Agent.
 *
 * <p>Same OSGi caveats as the other {@code Config} tests: node-type keys are left out so {@code NodeTypeRegistry}
 * is never reached, and {@code callUpdated} absorbs the {@code FrameworkService.sendEvent} that fires at the end
 * of {@code updated} outside a container.
 */
public class ConfigUserAgentTest {

    private static final String NS = "org.jahia.community.modules.customgpt";
    private static final String KEY_API_BASE_URL = NS + ".apiBaseUrl";
    private static final String KEY_USER_AGENT = NS + ".userAgent";

    private Config config;

    @Before
    public void setUp() {
        config = new Config();
    }

    @Test
    public void getUserAgent_isEmptyBeforeAnyUpdate() {
        assertThat(config.getUserAgent()).isEmpty();
    }

    @Test
    public void getUserAgent_isEmptyWhenNotConfigured() {
        callUpdated(minimalValidProps());

        assertThat(config.getUserAgent()).isEmpty();
    }

    @Test
    public void getUserAgent_returnsTheConfiguredValue() {
        final Dictionary<String, Object> props = minimalValidProps();
        props.put(KEY_USER_AGENT, "Mozilla/5.0 (compatible; JahiaIndexer/1.0; +https://academy.jahia.com)");

        callUpdated(props);

        assertThat(config.getUserAgent())
                .isEqualTo("Mozilla/5.0 (compatible; JahiaIndexer/1.0; +https://academy.jahia.com)");
    }

    @Test
    public void getUserAgent_trimsTheConfiguredValue() {
        final Dictionary<String, Object> props = minimalValidProps();
        props.put(KEY_USER_AGENT, "   JahiaIndexer/1.0   ");

        callUpdated(props);

        assertThat(config.getUserAgent()).isEqualTo("JahiaIndexer/1.0");
    }

    @Test
    public void getUserAgent_ignoresAValueThatWouldSplitTheHeader() {
        // OkHttp would throw IllegalArgumentException when building the request, failing every page fetch.
        final Dictionary<String, Object> props = minimalValidProps();
        props.put(KEY_USER_AGENT, "Bot/1.0\r\nX-Injected: yes");

        callUpdated(props);

        assertThat(config.getUserAgent()).isEmpty();
        // A rejected agent must not take the rest of the configuration down with it.
        assertThat(config.isConfigured()).isTrue();
    }

    @Test
    public void getUserAgent_forgetsAValueRemovedFromTheConfiguration() {
        final Dictionary<String, Object> first = minimalValidProps();
        first.put(KEY_USER_AGENT, "JahiaIndexer/1.0");
        callUpdated(first);

        callUpdated(minimalValidProps());

        assertThat(config.getUserAgent()).isEmpty();
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
