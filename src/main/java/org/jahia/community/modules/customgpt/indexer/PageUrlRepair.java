package org.jahia.community.modules.customgpt.indexer;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.jcr.RepositoryException;
import javax.jcr.query.Query;
import javax.servlet.ServletException;
import okhttp3.OkHttpClient;
import org.jahia.api.Constants;
import org.jahia.community.modules.customgpt.CustomGptConstants;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRTemplate;
import org.jahia.services.query.QueryWrapper;
import org.jahia.services.usermanager.JahiaUser;
import org.jahia.services.usermanager.JahiaUserManagerService;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Re-writes the metadata of indexed pages whose stored URL is missing or no longer canonical.
 *
 * <p>A page with no URL gives the chatbot a citation the user cannot click. 47 of 1920 pages in the production
 * corpus are in that state. The cause is <em>not</em> URL validation - version-range titles producing
 * 170-character paths store fine - but transient failure under bulk load: this API answers {@code status: success}
 * with an empty body while reporting zero errors, and the write is dropped. 36 of the 47 came from a single
 * high-volume day, scattered across separate minutes rather than one contiguous bad window.
 *
 * <p>A stale URL is as user-visible as a missing one: 16 pages under {@code /jahia-cloud/latest/} store a path
 * that answers 301 to a different canonical URL, because {@code latest} is an alias segment in that tree. Rather
 * than enumerate which trees use aliases, every page's URL is recomputed and compared.
 *
 * <p>Only the metadata write is re-issued. The pages exist with correct content and their mapping nodes are
 * intact, so there is nothing to re-render or re-upload: one request per affected page instead of a full
 * re-index. The URL is recomputed through the same {@link CustomGptIndexerNodeHandler#resolvePublicUrl} that
 * indexation uses, so a repaired URL is by construction the canonical vanity URL rather than a raw path.
 */
public class PageUrlRepair {

    private static final Logger LOGGER = LoggerFactory.getLogger(PageUrlRepair.class);
    private static final String PROP_URL = "url";

    private final OkHttpClient customGptClient;
    private final String projectId;
    private final String apiBaseUrl;

    private int repaired;
    private int intact;
    private int failed;

    public PageUrlRepair(OkHttpClient customGptClient, String projectId, String apiBaseUrl) {
        this.customGptClient = customGptClient;
        this.projectId = projectId;
        this.apiBaseUrl = apiBaseUrl;
    }

    /** @return the number of pages whose URL was repaired */
    public int repairSite(String siteKey) throws RepositoryException {
        final JahiaUser rootUser = JahiaUserManagerService.getInstance().lookupRootUser().getJahiaUser();
        final Map<String, String> mappings = collectPageMappings(siteKey);
        LOGGER.info("[repairPageUrls] {} indexed page(s) to examine for site {}", mappings.size(), siteKey);

        for (Map.Entry<String, String> entry : mappings.entrySet()) {
            repairOnePage(entry.getKey(), entry.getValue(), rootUser);
        }
        LOGGER.info("[repairPageUrls] Complete for site {} - {} examined, {} repaired, {} already correct,"
                + " {} could not be repaired", siteKey, mappings.size(), repaired, intact, failed);
        return repaired;
    }

    /**
     * Maps node path to CustomGPT page id for every mapping node under the site.
     *
     * <p>Walks the mapping nodes rather than querying by page id: {@code customGptPageId} is declared
     * {@code indexed=no}, so it cannot appear in a query constraint.
     */
    private Map<String, String> collectPageMappings(String siteKey) throws RepositoryException {
        return JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
            final Map<String, String> mappings = new LinkedHashMap<>();
            final QueryWrapper query = session.getWorkspace().getQueryManager().createQuery(
                    "SELECT * FROM [" + CustomGptConstants.NT_CUSTOM_GPT_INDEX_ENTRY + "] AS entry"
                            + " WHERE ISDESCENDANTNODE(entry, '" + CustomGptConstants.PATH_SITES + siteKey + "')",
                    Query.JCR_SQL2);
            for (JCRNodeWrapper entry : query.execute().getNodes()) {
                if (entry.hasProperty(CustomGptConstants.PROP_CUSTOM_GPT_PAGE_ID)) {
                    mappings.put(entry.getParent().getPath(),
                            entry.getProperty(CustomGptConstants.PROP_CUSTOM_GPT_PAGE_ID).getString());
                }
            }
            return mappings;
        });
    }

    /**
     * The URL currently stored for the page, or {@code null} when it carries none.
     *
     * <p>Every one of the 47 null-URL pages in production shows the same shape: the {@code url} key is present
     * and its value is JSON {@code null}. The key is never absent and never an empty string. The blank check is
     * defensive - if the API ever starts storing an empty string, repairing is the right response to it.
     */
    static String storedUrl(JSONObject data) {
        if (data == null || data.isNull(PROP_URL)) {
            return null;
        }
        final String url = data.optString(PROP_URL, "").trim();
        return url.isEmpty() ? null : url;
    }

    /**
     * Whether the stored URL needs rewriting: absent, or no longer the URL indexation would produce.
     *
     * <p>A stale URL is as user-visible as a missing one. 16 pages under {@code /jahia-cloud/latest/} store a path
     * that answers 301 to a different canonical URL, because {@code latest} is an alias segment in that tree - so
     * every citation to them costs the reader a redirect, and the stored path is not what the page is called.
     * Comparing against the recomputed URL catches that without needing to know which trees use aliases.
     */
    static boolean needsRepair(String storedUrl, String canonicalUrl) {
        return !canonicalUrl.equals(storedUrl);
    }

    private void repairOnePage(String nodePath, String pageId, JahiaUser rootUser) {
        try {
            final JSONObject data = CustomGptIndexerNodeHandler.fetchPageMetadata(customGptClient, projectId,
                    pageId, apiBaseUrl);
            final String stored = storedUrl(data);
            // readPage throws when the node is gone, which counts the page as failed and names it in the log.
            // That is the right outcome: a page whose node no longer exists needs deleting from the corpus, not
            // a rewritten URL, and this at least makes those visible instead of silently passing as intact.
            final PageToRepair page = readPage(nodePath, rootUser);
            if (!needsRepair(stored, page.url)) {
                intact++;
                return;
            }
            LOGGER.info("[repairPageUrls] Page {} ({}) stores {}; setting it to {}", pageId, nodePath, stored, page.url);
            // Goes through the same checked write as indexation, so the repair is itself verified by read-back
            // rather than trusting the 2xx that caused this state in the first place.
            CustomGptIndexerNodeHandler.updatePageMetadataChecked(customGptClient, projectId, pageId,
                    page.title, page.url, apiBaseUrl);
            repaired++;
        } catch (RepositoryException | IOException | RuntimeException e) {
            failed++;
            LOGGER.warn("[repairPageUrls] Could not repair the URL of page {} ({})", pageId, nodePath, e);
        }
    }

    /** The title and public URL to write back, read from the live workspace in one session. */
    private static PageToRepair readPage(String nodePath, JahiaUser rootUser) throws RepositoryException {
        return JCRTemplate.getInstance().doExecuteWithSystemSessionAsUser(rootUser, Constants.LIVE_WORKSPACE, null,
                session -> {
                    final JCRNodeWrapper node = session.getNode(nodePath);
                    final String title = node.hasProperty(Constants.JCR_TITLE)
                            ? node.getPropertyAsString(Constants.JCR_TITLE) : node.getName();
                    try {
                        return new PageToRepair(title,
                                CustomGptIndexerNodeHandler.resolvePublicUrl(node, node.getResolveSite(), rootUser));
                    } catch (IOException | ServletException | InvocationTargetException | URISyntaxException e) {
                        throw new RepositoryException("Cannot resolve the public URL of " + nodePath, e);
                    }
                });
    }

    private static final class PageToRepair {
        private final String title;
        private final String url;

        private PageToRepair(String title, String url) {
            this.title = title;
            this.url = url;
        }
    }
}
