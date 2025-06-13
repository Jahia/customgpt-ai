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
 * Utility methods used across the CustomGPT module.
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
            final Value[] values = node.getProperty(property).getValues();
            for (Value value : values) {
                result.add(value.getString());
            }
        } catch (RepositoryException e) {
            throw new JahiaRuntimeException(e);
        }

        return result;
    }

    public static String encode(String uri, RenderContext renderContext) throws IOException, ServletException, InvocationTargetException, URISyntaxException {
        return StringUtils.replaceEach(Utils.encodeLink(uri, true, renderContext, false), ENTITIES, ENCODED_ENTITIES);
    }

    public static String getHostName(JCRSiteNode siteNode) {
        final String hostName;
        try {
            final String sitemapIndexURL = siteNode.getPropertyAsString("sitemapIndexURL");
            final URL serverUrl = new URL(sitemapIndexURL);
            hostName = StringUtils.substringBeforeLast(sitemapIndexURL, serverUrl.getPath());
            return hostName;
        } catch (MalformedURLException e) {
            LOGGER.error("Something wrong happen while retrieving the hostname for the site {}, Sitemap generation won't happen", siteNode.getPath());
            LOGGER.debug("Detailed message", e);
        }
        return "";
    }

    public static JCRNodeWrapper getParentOfType(JCRSessionWrapper session, Config customGptConfig, String path) throws RepositoryException, NotConfiguredException {
        final String[] pathParts = path.split(CustomGptConstants.PATH_DELIMITER);
        if (pathParts.length > 2) {
            final String[] parentPathParts = Arrays.copyOf(pathParts, pathParts.length - 1);
            final String parentPath = String.join(CustomGptConstants.PATH_DELIMITER, parentPathParts);
            if (session.nodeExists(parentPath)) {
                final JCRNodeWrapper node = session.getNode(parentPath);
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
            } else {
                return getParentOfType(session, customGptConfig, parentPath);
            }
        }
        return null;
    }
    
    private static String encodeLink(String URIPath, boolean shouldBeDecodedFirst, RenderContext renderContext, boolean removeContextPath) throws IOException, ServletException, InvocationTargetException, URISyntaxException {
        final UrlRewriteService urlRewriteService = BundleUtils.getOsgiService(UrlRewriteService.class, null);
        String encodedURIPath = urlRewriteService.rewriteOutbound(URIPath, renderContext.getRequest(), renderContext.getResponse());

        if (removeContextPath) {
            encodedURIPath = RegExUtils.replaceFirst(encodedURIPath, renderContext.getRequest().getContextPath(), "");
        }

        if (shouldBeDecodedFirst) {
            encodedURIPath = URLDecoder.decode(encodedURIPath, StandardCharsets.UTF_8);
        }

        encodedURIPath = StringEscapeUtils.escapeXml10(encodedURIPath);
        encodedURIPath = new URI(null, null, encodedURIPath, null).toASCIIString();
        return encodedURIPath;
    }
}
