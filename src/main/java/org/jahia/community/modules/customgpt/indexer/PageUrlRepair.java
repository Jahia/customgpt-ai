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
 * Re-writes the metadata of pages this module indexed without a URL.
 *
 * <p>A page with no URL gives the chatbot a citation the user cannot click. 47 of 1920 pages in the production
 * corpus are in that state. The cause is <em>not</em> URL validation - version-range titles producing
 * 170-character paths store fine - but transient failure under bulk load: this API answers {@code status: success}
 * with an empty body while reporting zero errors, and the write is dropped. 36 of the 47 came from a single
 * high-volume day, scattered across separate minutes rather than one contiguous bad window.
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
        LOGGER.info("[repairPageUrls] Complete for site {} - {} examined, {} repaired, {} already had a URL,"
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
     * Whether the page already carries a usable URL.
     *
     * <p>Every one of the 47 affected pages in production shows the same shape: the {@code url} key is present
     * and its value is JSON {@code null}. The key is never absent and never an empty string. The blank check is
     * defensive - if the API ever starts storing an empty string, repairing is the right response to it.
     */
    static boolean hasStoredUrl(JSONObject data) {
        if (data == null || data.isNull(PROP_URL)) {
            return false;
        }
        return !data.optString(PROP_URL, "").trim().isEmpty();
    }

    private void repairOnePage(String nodePath, String pageId, JahiaUser rootUser) {
        try {
            final JSONObject data = CustomGptIndexerNodeHandler.fetchPageMetadata(customGptClient, projectId,
                    pageId, apiBaseUrl);
            if (hasStoredUrl(data)) {
                intact++;
                return;
            }
            final PageToRepair page = readPage(nodePath, rootUser);
            LOGGER.info("[repairPageUrls] Page {} ({}) has no URL; setting it to {}", pageId, nodePath, page.url);
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
