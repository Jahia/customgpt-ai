package org.jahia.community.modules.customgpt.indexer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.jcr.RepositoryException;
import javax.jcr.query.Query;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jahia.community.modules.customgpt.CustomGptConstants;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRTemplate;
import org.jahia.services.query.QueryWrapper;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds and removes pages in the CustomGPT project that no mapping node claims.
 *
 * <p>Roughly 60 pages in the production corpus answer 404 on their stored URL - content indexed and then removed
 * or moved without the corpus being updated, including a security advisory. Their URL is present, just dead, so
 * {@link PageUrlRepair} leaves them alone: the remedy is deleting the page, not rewriting its URL.
 *
 * <p>Detection is a repository fact, not a network observation. Mapping nodes are children of the content node
 * ({@code + customgptIndex (jnt:customGptIndexEntry)}), so deleting content deletes its mapping. A project page
 * whose id no mapping node holds is therefore a page nothing in Jahia accounts for. Probing URLs for a 404 would
 * instead flag the ~27% of the corpus behind auth, whose status cannot be observed anonymously, and would depend
 * on the academy being reachable at the moment of the sweep.
 *
 * <p>This is how a page gets orphaned in the first place: the delete never propagated - the stale-listener defect
 * in JAHIACOM-1675 is exactly that failure mode, and a failed delete used to be discarded silently.
 *
 * <p><strong>Blast radius.</strong> If the JCR side returns nothing, every page looks orphaned. The sweep refuses
 * to run in that case, and defaults to reporting rather than deleting.
 */
public class OrphanedPages {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrphanedPages.class);
    /** Stops a malformed pagination response from looping forever; far above any realistic project size. */
    private static final int MAX_RESULT_PAGES = 500;

    private final OkHttpClient customGptClient;
    private final String projectId;
    private final String apiBaseUrl;

    public OrphanedPages(OkHttpClient customGptClient, String projectId, String apiBaseUrl) {
        this.customGptClient = customGptClient;
        this.projectId = projectId;
        this.apiBaseUrl = apiBaseUrl;
    }

    /** One page as the CustomGPT project reports it. */
    public static final class ProjectPage {
        private final String id;
        private final String title;
        /** Filled from the per-page metadata endpoint; the pages LISTING reports null for every uploaded page. */
        private String url;

        public ProjectPage(String id, String title, String url) {
            this.id = id;
            this.title = title;
            this.url = url;
        }

        public String getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }

        public String getUrl() {
            return url;
        }

        void setUrl(String url) {
            this.url = url;
        }
    }

    /** The project pages whose id appears in no mapping node. */
    static List<ProjectPage> findOrphans(Set<String> knownPageIds, List<ProjectPage> projectPages) {
        final List<ProjectPage> orphans = new ArrayList<>();
        for (ProjectPage page : projectPages) {
            if (!knownPageIds.contains(page.getId())) {
                orphans.add(page);
            }
        }
        return orphans;
    }

    /**
     * Refuses a sweep when Jahia claims no pages at all.
     *
     * <p>An empty set means every project page would be classified as an orphan and deleted. That is far more
     * likely to be a failed query or a partially restored repository than a genuinely empty index, and the
     * corpus cannot be recovered from it.
     */
    static void assertSafeToSweep(Set<String> knownPageIds) throws IOException {
        if (knownPageIds.isEmpty()) {
            throw new IOException("Refusing to sweep orphaned pages: Jahia holds no mapping nodes at all, so every"
                    + " page in the project would be treated as orphaned. Check that the repository is fully"
                    + " started and that this module has indexed at least one page.");
        }
    }

    /**
     * Reports the orphaned pages, and deletes the ones named in {@code pageIds} when {@code dryRun} is false.
     *
     * <p>Detection is sound; the remedy is not automatic. A page no mapping node claims has two possible causes
     * with opposite remedies - content deleted in Jahia, where the page is residue, or a mapping node lost while
     * the content survives, where the page is the ONLY copy of that content and the remedy is to re-index the
     * node. Measured on production, 0 of 65 orphans were redundant and 18 held substantive unique content.
     *
     * <p>Telling the two apart needs the stored URL of every claimed page, which is thousands of metadata reads
     * and cannot be done inside one request. So this does not guess: it reports with real URLs, and deletes only
     * page ids a human has read and passed back.
     *
     * @return the number of orphaned pages found, or deleted when not a dry run
     */
    public int sweep(Collection<String> pageIds, boolean dryRun) throws IOException, RepositoryException {
        final Set<String> knownPageIds = collectKnownPageIds();
        assertSafeToSweep(knownPageIds);
        if (!dryRun) {
            assertDeletionIsScoped(pageIds);
        }

        final List<ProjectPage> projectPages = listProjectPages();
        final List<ProjectPage> orphans = findOrphans(knownPageIds, projectPages);
        enrichWithStoredUrls(orphans);

        LOGGER.info("[sweepOrphanedPages] {} page(s) in the project, {} claimed by a mapping node, {} orphaned",
                projectPages.size(), knownPageIds.size(), orphans.size());
        LOGGER.info("[sweepOrphanedPages] An orphan is NOT necessarily redundant. Before deleting any of these,"
                + " check whether a still-claimed page holds the same URL. If none does, this page is the only"
                + " copy of that content and the remedy is to re-index its node, not to delete it.");
        for (ProjectPage orphan : orphans) {
            LOGGER.info("[sweepOrphanedPages] Orphan: id={} title='{}' url={}",
                    orphan.getId(), orphan.getTitle(), orphan.getUrl() == null ? "<none stored>" : orphan.getUrl());
        }
        if (dryRun) {
            return orphans.size();
        }

        final List<ProjectPage> toDelete = selectForDeletion(orphans, pageIds);
        LOGGER.info("[sweepOrphanedPages] Deleting {} of {} orphan(s), as named by the caller",
                toDelete.size(), orphans.size());
        int deleted = 0;
        for (ProjectPage orphan : toDelete) {
            if (deletePage(orphan)) {
                deleted++;
            }
        }
        LOGGER.info("[sweepOrphanedPages] Deleted {} of {} named orphan(s)", deleted, toDelete.size());
        return deleted;
    }

    /**
     * Refuses a deletion that names no pages.
     *
     * <p>There is no safe "delete everything orphaned": the classification does not distinguish residue from the
     * last remaining copy of a page's content. A human reads the report and names what may go.
     */
    static void assertDeletionIsScoped(Collection<String> pageIds) throws IOException {
        if (pageIds == null || pageIds.isEmpty()) {
            throw new IOException("Refusing to delete orphaned pages without an explicit pageIds list. An orphan"
                    + " is not necessarily redundant - it may be the only copy of its content, in which case the"
                    + " remedy is to re-index its node. Run with dryRun to get the list, check each URL against"
                    + " the surviving pages, and pass back only the ids that are genuinely residue.");
        }
    }

    /** The orphans the caller named; naming an id that is not an orphan does not make it deletable. */
    static List<ProjectPage> selectForDeletion(List<ProjectPage> orphans, Collection<String> pageIds) {
        final Set<String> named = new HashSet<>(pageIds);
        final List<ProjectPage> selected = new ArrayList<>();
        for (ProjectPage orphan : orphans) {
            if (named.contains(orphan.getId())) {
                selected.add(orphan);
            }
        }
        return selected;
    }

    /**
     * Fills in each orphan's real URL from the per-page metadata endpoint.
     *
     * <p>Without this every orphan logs as {@code url=null}, because the pages listing reports no URL for an
     * uploaded page. That reads as "empty husk" and argues for deletion, when 18 of 20 sampled orphans held a
     * working citation link.
     */
    private void enrichWithStoredUrls(List<ProjectPage> orphans) {
        for (ProjectPage orphan : orphans) {
            try {
                orphan.setUrl(CustomGptIndexerNodeHandler.readStoredUrl(customGptClient, projectId,
                        orphan.getId(), apiBaseUrl));
            } catch (IOException e) {
                LOGGER.warn("[sweepOrphanedPages] Could not read the stored URL of orphan {}", orphan.getId(), e);
            }
        }
    }

    /**
     * Every page id held by a mapping node, across the whole repository.
     *
     * <p>Deliberately NOT scoped to one site. A project may hold pages from more than one site - a
     * {@code store.jahia.com} page reached the academy corpus before the registration gate existed - and scoping
     * the query to a single site would classify every other site's pages as orphaned.
     */
    private Set<String> collectKnownPageIds() throws RepositoryException {
        return JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
            final Set<String> ids = new HashSet<>();
            final QueryWrapper query = session.getWorkspace().getQueryManager().createQuery(
                    "SELECT * FROM [" + CustomGptConstants.NT_CUSTOM_GPT_INDEX_ENTRY + "]", Query.JCR_SQL2);
            for (JCRNodeWrapper entry : query.execute().getNodes()) {
                if (entry.hasProperty(CustomGptConstants.PROP_CUSTOM_GPT_PAGE_ID)) {
                    ids.add(entry.getProperty(CustomGptConstants.PROP_CUSTOM_GPT_PAGE_ID).getString());
                }
            }
            return ids;
        });
    }

    /** Every page in the project, following the API's pagination. */
    private List<ProjectPage> listProjectPages() throws IOException {
        final List<ProjectPage> pages = new ArrayList<>();
        for (int resultPage = 1; resultPage <= MAX_RESULT_PAGES; resultPage++) {
            final JSONArray items = fetchResultPage(resultPage);
            if (items == null || items.length() == 0) {
                return pages;
            }
            for (int i = 0; i < items.length(); i++) {
                final JSONObject item = items.getJSONObject(i);
                // The listing's url field is null for every uploaded page in this corpus (all is_file: true),
                // so it is deliberately not read here. The real URL comes from the per-page metadata endpoint
                // and is fetched for orphans only, in enrichWithStoredUrls.
                pages.add(new ProjectPage(String.valueOf(item.get("id")), item.optString("filename", null), null));
            }
        }
        throw new IOException("Stopped listing CustomGPT pages after " + MAX_RESULT_PAGES
                + " result pages; the pagination response looks malformed");
    }

    private JSONArray fetchResultPage(int resultPage) throws IOException {
        final Request request = new Request.Builder()
                .url(String.format("%s/projects/%s/pages?page=%d", apiBaseUrl, projectId, resultPage))
                .get()
                .build();
        try (Response response = customGptClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Failed to list CustomGPT pages (result page " + resultPage
                        + "): HTTP " + response.code());
            }
            final JSONObject data = new JSONObject(response.body().string()).optJSONObject("data");
            final JSONObject paged = data == null ? null : data.optJSONObject("pages");
            return paged == null ? null : paged.optJSONArray("data");
        }
    }

    private boolean deletePage(ProjectPage orphan) {
        final Request request = new Request.Builder()
                .url(String.format("%s/projects/%s/pages/%s", apiBaseUrl, projectId, orphan.getId()))
                .delete()
                .build();
        try (Response response = customGptClient.newCall(request).execute()) {
            // 403 means the page is already gone; the API answers a delete for an unknown id that way rather
            // than 404, which is measured behaviour and the same classification indexation uses.
            if (CustomGptIndexerNodeHandler.isPageGone(response.code())) {
                LOGGER.info("[sweepOrphanedPages] Deleted orphaned page {} ({})", orphan.getId(), orphan.getUrl());
                return true;
            }
            LOGGER.warn("[sweepOrphanedPages] Failed to delete orphaned page {} (HTTP {})",
                    orphan.getId(), response.code());
            return false;
        } catch (IOException e) {
            LOGGER.warn("[sweepOrphanedPages] Error deleting orphaned page {}", orphan.getId(), e);
            return false;
        }
    }
}
