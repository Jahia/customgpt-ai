package org.jahia.community.modules.customgpt.util;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import javax.servlet.ServletException;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.RegExUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.jahia.community.modules.customgpt.CustomGptConstants;
import org.jahia.community.modules.customgpt.settings.Config;
import org.jahia.community.modules.customgpt.settings.NotConfiguredException;
import org.jahia.exceptions.JahiaRuntimeException;
import org.jahia.osgi.BundleUtils;
import org.jahia.services.content.JCRContentUtils;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.jahia.services.render.RenderContext;
import org.jahia.services.seo.urlrewrite.UrlRewriteService;
import org.jahia.services.usermanager.JahiaUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Static utility methods shared across the module: URL encoding, hostname extraction, and JCR path resolution.
 */
public final class Utils {

    private static final Logger LOGGER = LoggerFactory.getLogger(Utils.class);
    private static final String[] ENTITIES = new String[]{"&amp;", "&apos;", "&quot;", "&gt;", "&lt;"};
    private static final String[] ENCODED_ENTITIES = new String[]{"_-amp-_", "_-apos-_", "_-quot-_", "_-gt-_", "_-lt-_"};

    private Utils() {
    }

    public static CustomGptConstants.IndexType getIndexType(JCRNodeWrapper node) {
        return node.isFile() ? CustomGptConstants.IndexType.FILE : CustomGptConstants.IndexType.CONTENT;
    }

    public static Set<String> getPropertyValuesAsSet(JCRNodeWrapper node, String property) {
        final Set<String> result = new HashSet<>();
        try {
            if (!node.hasProperty(property)) {
                return result;
            }
            final Value[] values = node.getProperty(property).getValues();
            for (Value value : values) {
                result.add(value.getString());
            }
        } catch (RepositoryException e) {
            throw new JahiaRuntimeException(e);
        }

        return result;
    }

    /**
     * Rewrites {@code uri} through Jahia's {@link UrlRewriteService} then applies XML entity escaping and
     * percent-encoding so it can be safely embedded in a sitemap or HTML attribute.
     */
    public static String encode(String uri, RenderContext renderContext) throws IOException, ServletException, InvocationTargetException, URISyntaxException {
        return StringUtils.replaceEach(Utils.encodeLink(uri, true, renderContext, false), ENTITIES, ENCODED_ENTITIES);
    }

    /**
     * Same as {@link #encode(String, RenderContext)}, with {@code user} bound as the JCR thread-local current user
     * for the duration of the call.
     *
     * <p>Jahia resolves vanity URLs inside {@code rewriteOutbound} through a JCR lookup that reads
     * {@code JCRSessionFactory.getCurrentUser()}. Indexation runs on a pooled executor thread, where the session is
     * opened with {@code doExecuteWithSystemSessionAsUser}: that binds the SESSION's user, not the thread-local one.
     * The vanity lookup therefore degraded to the raw {@code .html} path, Jahia answered it with a 302 to the vanity
     * URL, and this module does not follow redirects - so the page was never fetched and never indexed.
     *
     * <p>The previous value is restored in a {@code finally}. These threads are pooled and reused, so leaving a user
     * bound would leak an identity into whatever task ran next on the same thread.
     */
    public static String encode(String uri, RenderContext renderContext, JahiaUser user)
            throws IOException, ServletException, InvocationTargetException, URISyntaxException {
        final JahiaUser previous = JCRSessionFactory.getInstance().getCurrentUser();
        JCRSessionFactory.getInstance().setCurrentUser(user);
        try {
            return encode(uri, renderContext);
        } finally {
            JCRSessionFactory.getInstance().setCurrentUser(previous);
        }
    }

    /**
     * The server name this site's pages are indexed under: the configured override when there is one, otherwise the
     * host derived from the site's {@code sitemapIndexURL}.
     *
     * <p>The override is what lets a site be indexed under a host its own node does not name — a preproduction
     * instance restored from a production export still carries the production {@code sitemapIndexURL}, and a site may
     * carry none at all. See {@link Config#getServerName(String)} for the resolution order.
     *
     * @param customGptConfig the module configuration; a null config simply means "no override"
     * @return {@code scheme://host[:port]}, or an empty string when neither source yields a usable host
     */
    public static String getHostName(JCRSiteNode siteNode, Config customGptConfig) {
        final String configured = customGptConfig == null ? "" : customGptConfig.getServerName(siteNode.getSiteKey());
        if (StringUtils.isNotEmpty(configured)) {
            LOGGER.debug("Indexing site {} under the configured server name {}",
                    SecurityUtils.sanitizeForLog(siteNode.getSiteKey()), SecurityUtils.sanitizeForLog(configured));
            return configured;
        }
        return getHostName(siteNode);
    }

    /** The host derived from the site's {@code sitemapIndexURL}, ignoring any configured override. */
    public static String getHostName(JCRSiteNode siteNode) {
        final String hostName;
        try {
            final String sitemapIndexURL = siteNode.getPropertyAsString("sitemapIndexURL");
            final URL serverUrl = URI.create(sitemapIndexURL).toURL();
            // Nothing is fetched from this host - rendering goes to the local GraphQL endpoint - so it is only ever
            // the citation URL stored in CustomGPT. A literal private/loopback/link-local address is refused
            // because a citation nobody outside the network can open is worse than no citation.
            if (SecurityUtils.isInternalHost(serverUrl.getHost())) {
                LOGGER.error("Refusing to index site {}: sitemapIndexURL host resolves to an internal/private address,"
                        + " which would be stored as an unreachable citation URL", siteNode.getPath());
                return "";
            }
            hostName = StringUtils.substringBeforeLast(sitemapIndexURL, serverUrl.getPath());
            return hostName;
        } catch (MalformedURLException | IllegalArgumentException e) {
            LOGGER.error("Something wrong happen while retrieving the hostname for the site {}, Sitemap generation won't happen", siteNode.getPath());
            LOGGER.debug("Detailed message", e);
        }
        return "";
    }

    /**
     * Walks up the path segments of a (potentially deleted) node until it finds an ancestor that still exists in the
     * session and matches one of the configured indexed main-resource types. Used to resolve the indexable ancestor
     * when a node has already been removed from the repository.
     */
    public static JCRNodeWrapper getParentOfType(JCRSessionWrapper session, Config customGptConfig, String path) throws RepositoryException, NotConfiguredException {
        final String[] pathParts = path.split(CustomGptConstants.PATH_DELIMITER);
        if (pathParts.length <= 2) {
            return null;
        }
        final String[] parentPathParts = Arrays.copyOf(pathParts, pathParts.length - 1);
        final String parentPath = String.join(CustomGptConstants.PATH_DELIMITER, parentPathParts);
        if (!session.nodeExists(parentPath)) {
            return getParentOfType(session, customGptConfig, parentPath);
        }
        return findMainResourceAncestor(session.getNode(parentPath), customGptConfig);
    }

    private static JCRNodeWrapper findMainResourceAncestor(JCRNodeWrapper node, Config customGptConfig) throws RepositoryException, NotConfiguredException {
        for (String type : customGptConfig.getContentIndexedMainResources()) {
            if (node.isNodeType(type)) {
                return node;
            }
        }
        for (String type : customGptConfig.getContentIndexedMainResources()) {
            final JCRNodeWrapper parentNode = JCRContentUtils.getParentOfType(node, type);
            if (parentNode != null) {
                return parentNode;
            }
        }
        return null;
    }
    
    private static String encodeLink(String uriPath, boolean shouldBeDecodedFirst, RenderContext renderContext, boolean removeContextPath) throws IOException, ServletException, InvocationTargetException, URISyntaxException {
        final UrlRewriteService urlRewriteService = BundleUtils.getOsgiService(UrlRewriteService.class, null);
        String encodedUriPath = urlRewriteService.rewriteOutbound(uriPath, renderContext.getRequest(), renderContext.getResponse());

        if (removeContextPath) {
            encodedUriPath = RegExUtils.replaceFirst(encodedUriPath, renderContext.getRequest().getContextPath(), "");
        }

        if (shouldBeDecodedFirst) {
            encodedUriPath = URLDecoder.decode(encodedUriPath, StandardCharsets.UTF_8);
        }

        encodedUriPath = StringEscapeUtils.escapeXml10(encodedUriPath);
        encodedUriPath = new URI(null, null, encodedUriPath, null).toASCIIString();
        return encodedUriPath;
    }
}
