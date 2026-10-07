package org.jahia.community.modules.customgpt.indexer;

import okhttp3.Request;
import org.jahia.community.modules.customgpt.settings.Config;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests the User-Agent applied to the request that fetches a page's rendered HTML from Jahia.
 *
 * <p>Some sites sit behind bot protection (a WAF, a CDN rule, or Jahia's own filtering) that rejects the default
 * OkHttp agent. Such a site simply cannot be indexed: the fetch is refused before any content is produced, and
 * the module records a per-node failure with no indication that the agent is the reason. Making the agent
 * configurable lets the indexer identify itself as whatever that site's rules allow.
 *
 * <p>JUnit 4: the jahia-modules parent pins the {@code surefire-junit4} provider.
 */
public class RenderRequestUserAgentTest {

    private static final String URL = "https://academy.jahia.com/home.html";
    private static final String USER_AGENT = "User-Agent";

    private static Config configWithUserAgent(String userAgent) {
        final Config config = mock(Config.class);
        when(config.getUserAgent()).thenReturn(userAgent);
        when(config.getJahiaUsername()).thenReturn("");
        when(config.getJahiaPassword()).thenReturn("");
        return config;
    }

    @Test
    public void buildRenderRequest_sendsTheConfiguredUserAgent() {
        final Config config = configWithUserAgent("Mozilla/5.0 (compatible; JahiaIndexer/1.0)");

        final Request request = CustomGptIndexerNodeHandler.buildRenderRequest(URL, config);

        assertThat(request.header(USER_AGENT)).isEqualTo("Mozilla/5.0 (compatible; JahiaIndexer/1.0)");
    }

    @Test
    public void buildRenderRequest_omitsTheHeaderWhenNoneIsConfigured() {
        // Absent, not empty: an empty User-Agent is itself a bot signature on some WAFs, so leave OkHttp's default.
        final Request request = CustomGptIndexerNodeHandler.buildRenderRequest(URL, configWithUserAgent(""));

        assertThat(request.header(USER_AGENT)).isNull();
    }

    @Test
    public void buildRenderRequest_toleratesANullUserAgent() {
        final Request request = CustomGptIndexerNodeHandler.buildRenderRequest(URL, configWithUserAgent(null));

        assertThat(request.header(USER_AGENT)).isNull();
    }

    @Test
    public void buildRenderRequest_setsExactlyOneUserAgentHeader() {
        // addHeader appends rather than replaces; two User-Agent values is a malformed request.
        final Request request = CustomGptIndexerNodeHandler.buildRenderRequest(URL, configWithUserAgent("Bot/2.0"));

        assertThat(request.headers().values(USER_AGENT)).containsExactly("Bot/2.0");
    }

    @Test
    public void buildRenderRequest_stillAttachesBasicAuthOverHttps() {
        // The agent must not displace the existing credential handling.
        final Config config = mock(Config.class);
        when(config.getUserAgent()).thenReturn("Bot/2.0");
        when(config.getJahiaUsername()).thenReturn("indexer");
        when(config.getJahiaPassword()).thenReturn("s3cret");

        final Request request = CustomGptIndexerNodeHandler.buildRenderRequest(URL, config);

        assertThat(request.header("Authorization")).startsWith("Basic ");
        assertThat(request.header(USER_AGENT)).isEqualTo("Bot/2.0");
    }

    @Test
    public void buildRenderRequest_sendsTheAgentOverHttpTooEvenThoughCredentialsAreWithheld() {
        // Bot protection is exactly as likely on an http host, and the agent carries no secret.
        final Config config = mock(Config.class);
        when(config.getUserAgent()).thenReturn("Bot/2.0");
        when(config.getJahiaUsername()).thenReturn("indexer");
        when(config.getJahiaPassword()).thenReturn("s3cret");

        final Request request = CustomGptIndexerNodeHandler.buildRenderRequest("http://jahia.localhost:8080/p.html", config);

        assertThat(request.header("Authorization")).isNull();
        assertThat(request.header(USER_AGENT)).isEqualTo("Bot/2.0");
    }
}
