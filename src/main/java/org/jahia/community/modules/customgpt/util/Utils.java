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
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.jahia.services.render.RenderContext;
import org.jahia.services.seo.urlrewrite.UrlRewriteService;
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

    public static String getHostName(JCRSiteNode siteNode) {
        final String hostName;
        try {
            final String sitemapIndexURL = siteNode.getPropertyAsString("sitemapIndexURL");
            final URL serverUrl = URI.create(sitemapIndexURL).toURL();
            // The rendering request built from this host carries Jahia Basic-auth credentials; reject a host that is a
            // literal private/loopback/link-local IP so credentials cannot be sent to an internal SSRF target.
            if (SecurityUtils.isInternalHost(serverUrl.getHost())) {
                LOGGER.error("Refusing to render site {}: sitemapIndexURL host resolves to an internal/private address", siteNode.getPath());
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
