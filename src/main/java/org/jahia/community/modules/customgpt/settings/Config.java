package org.jahia.community.modules.customgpt.settings;

import java.util.*;
import java.util.regex.Pattern;
import javax.jcr.RepositoryException;
import javax.jcr.nodetype.NoSuchNodeTypeException;
import org.apache.commons.lang.StringUtils;
import org.jahia.community.modules.customgpt.CustomGptConstants;
import org.jahia.community.modules.customgpt.util.SecurityUtils;
import org.jahia.osgi.FrameworkService;
import org.jahia.services.content.nodetypes.NodeTypeRegistry;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedService;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OSGi {@link ManagedService} that reads the {@code org.jahia.community.modules.customgpt} configuration and
 * publishes an OSGi event on every update. The event type is {@link CustomGptConstants#EVENT_TYPE_CONFIG_UPDATED_REQUIRE_REINDEX}
 * when {@code scheduleJobASAP=true} (triggering immediate re-indexation), or
 * {@link CustomGptConstants#EVENT_TYPE_CONFIG_UPDATED} otherwise.
 */
@Component(service = {ManagedService.class, Config.class}, property = {
    "service.pid=org.jahia.community.modules.customgpt",
    "service.description=CustomGPT.ai configuration service",
    "service.vendor=Jahia Solutions Group SA"
}, immediate = true)
public class Config implements ManagedService {

    private static final Logger LOGGER = LoggerFactory.getLogger(Config.class);
    private static final String JMIX_MAIN_RESOURCE = "jmix:mainResource";
    private static final int DEFAULT_BULK_OPERATIONS_BATCH_SIZE = 500;

    private static final String CONFIG_NAMESPACE_PREFIX = "org.jahia.community.modules.customgpt";
    private static final String PROP_CONTENT_INDEXED_SUB_NODE_TYPES = CONFIG_NAMESPACE_PREFIX + ".content.indexedSubNodeTypes";
    private static final String PROP_CONTENT_INDEXED_MAIN_RESOURCE_TYPES = CONFIG_NAMESPACE_PREFIX + ".content.indexedMainResourceTypes";
    private static final String PROP_CUSTOM_GPT_PROJECT_ID = CONFIG_NAMESPACE_PREFIX + ".projectId";
    private static final String PROP_CUSTOM_GPT_TOKEN = CONFIG_NAMESPACE_PREFIX + ".token";
    private static final String PROP_JAHIA_USERNAME = CONFIG_NAMESPACE_PREFIX + ".jahia.username";
    private static final String PROP_JAHIA_PASSWORD = CONFIG_NAMESPACE_PREFIX + ".jahia.password";
    private static final String PROP_JAHIA_SERVER_COOKIE_NAME = CONFIG_NAMESPACE_PREFIX + ".jahia.serverCookie.name";
    private static final String PROP_JAHIA_SERVER_COOKIE_VALUE = CONFIG_NAMESPACE_PREFIX + ".jahia.serverCookie.value";
    private static final String PROP_JAHIA_SERVER_COOKIE_DOMAIN = CONFIG_NAMESPACE_PREFIX + ".jahia.serverCookie.domain";
    private static final String CONTENT_INDEXED_FILE_EXTENSIONS = CONFIG_NAMESPACE_PREFIX + ".content.indexedFileExtensions";
    private static final String BULK_OPERATIONS_BATCH_SIZE = CONFIG_NAMESPACE_PREFIX + ".operations.batch.size";
    private static final String SCHEDULE_JOB_ASAP = CONFIG_NAMESPACE_PREFIX + ".scheduleJobASAP";
    private static final String DRY_RUN = CONFIG_NAMESPACE_PREFIX + ".dryRun";
    private static final String PROP_CUSTOM_GPT_API_BASE_URL = CONFIG_NAMESPACE_PREFIX + ".apiBaseUrl";
    private static final String PROP_RATE_LIMIT_REQUESTS_PER_SECOND = CONFIG_NAMESPACE_PREFIX + ".rateLimit.requestsPerSecond";
    /** Server name applied to every site that has no per-site entry; see {@link #getServerName(String)}. */
    private static final String PROP_SERVER_NAME = CONFIG_NAMESPACE_PREFIX + ".serverName";
    /** Per-site server name: {@code <prefix>.site.<siteKey>.serverName}. */
    private static final String SITE_PROP_PREFIX = CONFIG_NAMESPACE_PREFIX + ".site.";
    private static final String SITE_PROP_SERVER_NAME_SUFFIX = ".serverName";
    private static final String SITE_PROP_PREFIX_LOWER = SITE_PROP_PREFIX.toLowerCase(Locale.ROOT);
    private static final String SITE_PROP_SERVER_NAME_SUFFIX_LOWER = SITE_PROP_SERVER_NAME_SUFFIX.toLowerCase(Locale.ROOT);
    /** A JCR site key is a single safe segment; anything else in the key is a typo, not a site. */
    private static final Pattern SITE_KEY_PATTERN = Pattern.compile("^[\\w-]+$");

    private Set<String> contentIndexedMainResources;
    private Set<String> contentIndexedSubNodes;
    private Set<String> indexedFileExtensions;
    private boolean configured = false;
    private boolean scheduleJobASAP;
    private boolean dryRun;
    private int bulkOperationsBatchSize;
    private String customGptProjectId;
    private String customGptToken;
    private String jahiaUsername;
    private String jahiaPassword;
    private String jahiaServerCookieName;
    private String jahiaServerCookieValue;
    private String jahiaServerCookieDomain;
    private String customGptApiBaseUrl;
    private int rateLimitRequestsPerSecond;
    /**
     * The server-name overrides, published as one immutable snapshot. Written by the ConfigAdmin thread and read by
     * the indexation threads, so the two halves must swap together: a reader that saw a new instance-wide default
     * next to the previous per-site map would index a site under a host neither setting ever named.
     */
    private volatile ServerNames serverNames = ServerNames.NONE;

    /**
     * Called by OSGi ConfigurationAdmin whenever the {@code org.jahia.community.modules.customgpt.cfg} file changes.
     * Parses all properties then fires an OSGi event so {@link org.jahia.community.modules.customgpt.service.Service}
     * can reinitialise its HTTP clients and optionally kick off re-indexation.
     */
    @Override
    public void updated(Dictionary<String, ?> properties) throws ConfigurationException {
        if (properties == null) {
            return;
        }
        parse(properties);
        // The apiBaseUrl travels with the Bearer token on every API call. A .cfg edit bypasses the saveSettings UI
        // gate, so re-validate here: reject anything that is not a public https:// URL and refuse to mark the
        // service configured, rather than letting the token be sent over cleartext or to an internal SSRF target.
        if (StringUtils.isNotEmpty(customGptApiBaseUrl) && !SecurityUtils.isHttpsUrl(customGptApiBaseUrl)) {
            LOGGER.error("CustomGpt apiBaseUrl is not a valid public https:// URL; configuration rejected");
            configured = false;
            return;
        }
        configured = true;
        final String eventType = scheduleJobASAP
                ? CustomGptConstants.EVENT_TYPE_CONFIG_UPDATED_REQUIRE_REINDEX
                : CustomGptConstants.EVENT_TYPE_CONFIG_UPDATED;
        FrameworkService.sendEvent(CustomGptConstants.EVENT_TOPIC,
                Collections.singletonMap("type", eventType), true);
        LOGGER.info("CustomGpt configuration loaded");
    }

    public Set<String> getContentIndexedSubNodes() throws NotConfiguredException {
        checkConfigured();
        return new LinkedHashSet<>(contentIndexedSubNodes);
    }

    public Set<String> getContentIndexedMainResources() throws NotConfiguredException {
        checkConfigured();
        return new LinkedHashSet<>(contentIndexedMainResources);
    }

    public Set<String> getIndexedFileExtensions() {
        return new LinkedHashSet<>(indexedFileExtensions);
    }

    public int getBulkOperationsBatchSize() throws NotConfiguredException {
        checkConfigured();
        return bulkOperationsBatchSize;

    }

    private void checkConfigured() throws NotConfiguredException {
        if (!configured) {
            throw new NotConfiguredException("customGpt search provider is not configured");
        }
    }

    private void parse(Dictionary<String, ?> properties) {
        // Populate sets of properties
        contentIndexedSubNodes = splitNodeTypeByComma((String) properties.get(PROP_CONTENT_INDEXED_SUB_NODE_TYPES));
        contentIndexedMainResources = splitNodeTypeByComma((String) properties.get(PROP_CONTENT_INDEXED_MAIN_RESOURCE_TYPES));
        updateSetToExcludeMainResourceType(contentIndexedMainResources);
        bulkOperationsBatchSize = getInt(properties, BULK_OPERATIONS_BATCH_SIZE, DEFAULT_BULK_OPERATIONS_BATCH_SIZE);

        final String indexedFiles = (String) properties.get(CONTENT_INDEXED_FILE_EXTENSIONS);
        if (StringUtils.isEmpty(StringUtils.trim(indexedFiles))) {
            indexedFileExtensions = Collections.emptySet();
        } else {
            // indexedFiles is non-blank here (the isEmpty branch above handles null/blank), so no null guard needed.
            indexedFileExtensions = new LinkedHashSet<>();
            for (String part : indexedFiles.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    indexedFileExtensions.add(trimmed);
                }
            }
        }

        scheduleJobASAP = getBoolean(properties, SCHEDULE_JOB_ASAP, false);
        dryRun = getBoolean(properties, DRY_RUN, false);
        customGptApiBaseUrl = getString(properties, PROP_CUSTOM_GPT_API_BASE_URL, CustomGptConstants.DEFAULT_CUSTOM_GPT_API_BASE_URL);
        rateLimitRequestsPerSecond = getInt(properties, PROP_RATE_LIMIT_REQUESTS_PER_SECOND, 10);

        customGptProjectId = getString(properties, PROP_CUSTOM_GPT_PROJECT_ID, "");
        customGptToken = getString(properties, PROP_CUSTOM_GPT_TOKEN, "");
        jahiaUsername = getString(properties, PROP_JAHIA_USERNAME, "");
        jahiaPassword = getString(properties, PROP_JAHIA_PASSWORD, "");
        jahiaServerCookieName = getString(properties, PROP_JAHIA_SERVER_COOKIE_NAME, "");
        jahiaServerCookieValue = getString(properties, PROP_JAHIA_SERVER_COOKIE_VALUE, "");
        jahiaServerCookieDomain = getString(properties, PROP_JAHIA_SERVER_COOKIE_DOMAIN, "");

        // Replaced wholesale on every update rather than merged, so an override removed from the .cfg is forgotten.
        serverNames = new ServerNames(
                normalizeConfiguredServerName(getString(properties, PROP_SERVER_NAME, ""), PROP_SERVER_NAME),
                parseSiteServerNames(properties));
    }

    /**
     * Validates and normalises one configured server name, logging and discarding anything unusable.
     *
     * <p>Discarding rather than failing is deliberate: the caller then falls back to the site's
     * {@code sitemapIndexURL}, so a typo degrades to the previous behaviour instead of stopping indexation or,
     * worse, prefixing every indexed URL with a host that does not resolve.
     */
    private String normalizeConfiguredServerName(String configured, String key) {
        if (StringUtils.isEmpty(StringUtils.trim(configured))) {
            return "";
        }
        final String normalized = SecurityUtils.normalizeServerName(configured);
        if (normalized.isEmpty()) {
            // Deliberately not "the sitemapIndexURL will be used": a rejected per-site value leaves the site on the
            // instance-wide serverName when one is set, and only falls through to sitemapIndexURL when none is.
            LOGGER.error("Ignoring {}: '{}' is not an absolute http(s) URL whose host is a public address."
                    + " This site will be indexed under the instance-wide serverName if one is set, otherwise under"
                    + " its own sitemapIndexURL.",
                    key, SecurityUtils.sanitizeForLog(configured));
        }
        return normalized;
    }

    /** Collects every {@code <prefix>.site.<siteKey>.serverName} entry that names a plausible site key. */
    private Map<String, String> parseSiteServerNames(Dictionary<String, ?> properties) {
        final Map<String, String> parsed = new LinkedHashMap<>();
        final Enumeration<String> keys = properties.keys();
        while (keys.hasMoreElements()) {
            final String key = keys.nextElement();
            // Matched case-insensitively because ConfigAdmin delivers a case-insensitive dictionary: a key typed
            // with different casing is readable through get() but would be invisible to a case-sensitive scan,
            // silently dropping the override. The site key itself is kept verbatim - JCR site keys are not.
            final String lowerKey = key == null ? "" : key.toLowerCase(Locale.ROOT);
            if (!lowerKey.startsWith(SITE_PROP_PREFIX_LOWER) || !lowerKey.endsWith(SITE_PROP_SERVER_NAME_SUFFIX_LOWER)) {
                continue;
            }
            final String siteKey = key.substring(SITE_PROP_PREFIX.length(),
                    key.length() - SITE_PROP_SERVER_NAME_SUFFIX.length());
            if (!SITE_KEY_PATTERN.matcher(siteKey).matches()) {
                LOGGER.error("Ignoring {}: '{}' is not a valid site key", key, SecurityUtils.sanitizeForLog(siteKey));
                continue;
            }
            final String normalized = normalizeConfiguredServerName(getString(properties, key, ""), key);
            if (!normalized.isEmpty()) {
                parsed.put(siteKey, normalized);
            }
        }
        return Collections.unmodifiableMap(parsed);
    }

    /**
     * The server name ({@code scheme://host[:port]}) this site's pages must be indexed under, or an empty string
     * when the site's own {@code sitemapIndexURL} should be used.
     *
     * <p>Resolution order: the site's own {@code <prefix>.site.<siteKey>.serverName}, then the instance-wide
     * {@code <prefix>.serverName}, then empty. The override exists for the cases where the site node does not carry
     * the host its pages are actually served from — a preproduction instance restored from a production export still
     * names the production host in {@code sitemapIndexURL}, and a site may carry no {@code sitemapIndexURL} at all.
     *
     * <p>The value returned is already normalised and SSRF-checked (see
     * {@link SecurityUtils#normalizeServerName(String)}); a rejected value reads here as "not configured".
     *
     * @param siteKey the key of the site being indexed; null or empty resolves the instance-wide default
     */
    public String getServerName(String siteKey) {
        // Read once: a second read could land on a newer snapshot and mix the two tiers.
        final ServerNames current = serverNames;
        if (StringUtils.isNotEmpty(siteKey)) {
            final String perSite = current.bySite.get(siteKey);
            if (perSite != null) {
                return perSite;
            }
        }
        return current.instanceWide;
    }

    /** Immutable pair of server-name tiers, swapped as a unit on every configuration update. */
    private static final class ServerNames {

        private static final ServerNames NONE = new ServerNames("", Collections.emptyMap());

        private final String instanceWide;
        private final Map<String, String> bySite;

        private ServerNames(String instanceWide, Map<String, String> bySite) {
            this.instanceWide = instanceWide;
            this.bySite = bySite;
        }
    }

    private Set<String> splitNodeTypeByComma(String commaSeparated) {
        final Set<String> nodetypes = new LinkedHashSet<>();
        if (StringUtils.isEmpty(commaSeparated)) {
            return nodetypes;
        }
        for (String nodeType : commaSeparated.split(",")) {
            String trimmed = nodeType.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                NodeTypeRegistry.getInstance().getNodeType(trimmed);
                nodetypes.add(trimmed);
            } catch (RepositoryException e) {
                LOGGER.warn("unable to register nodetype [{}] from config file. {}", trimmed, e.getMessage());
            }
        }

        return nodetypes;
    }

    private int getInt(Dictionary<String, ?> properties, String key, int def) {
        final Object val = properties.get(key);
        if (val == null) {
            return def;
        }
        if (val instanceof Number) {
            return ((Number) val).intValue();
        }
        return Integer.parseInt(val.toString());
    }

    private boolean getBoolean(Dictionary<String, ?> properties, String key, boolean def) {
        final Object val = properties.get(key);
        if (val == null) {
            return def;
        }
        if (val instanceof Boolean) {
            return (Boolean) val;
        }
        return Boolean.parseBoolean(val.toString());
    }

    private String getString(Dictionary<String, ?> properties, String key, String def) {
        final Object val = properties.get(key);
        return val != null ? val.toString() : def;
    }

    public boolean isConfigured() {
        return configured;
    }

    /**
     * Removes from {@code set} any type that extends {@code jmix:mainResource} when {@code jmix:mainResource} itself
     * is already in the set, avoiding double-indexing of concrete subtypes.
     */
    private void updateSetToExcludeMainResourceType(Set<String> set) {
        // Main resource is not set so don't try to exclude types that may extend it
        if (!set.contains(JMIX_MAIN_RESOURCE)) {
            return;
        }

        set.removeIf(type -> {
            try {
                return !type.equals(JMIX_MAIN_RESOURCE) && NodeTypeRegistry.getInstance().getNodeType(type).isNodeType(JMIX_MAIN_RESOURCE);
            } catch (NoSuchNodeTypeException e) {
                LOGGER.error("Failed to get information about node type: {}", e.getMessage());
                return true;
            }
        });
    }

    public boolean isScheduleJobASAP() {
        return scheduleJobASAP;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public String getCustomGptProjectId() {
        return customGptProjectId;
    }

    public String getCustomGptToken() {
        return customGptToken;
    }

    public String getJahiaUsername() {
        return jahiaUsername;
    }

    public String getJahiaPassword() {
        return jahiaPassword;
    }

    public String getJahiaServerCookieName() {
        return jahiaServerCookieName;
    }

    public String getJahiaServerCookieValue() {
        return jahiaServerCookieValue;
    }
    public String getJahiaServerCookieDomain() {
        return jahiaServerCookieDomain;
    }

    public String getCustomGptApiBaseUrl() {
        return customGptApiBaseUrl;
    }

    public int getRateLimitRequestsPerSecond() {
        return rateLimitRequestsPerSecond;
    }
}
