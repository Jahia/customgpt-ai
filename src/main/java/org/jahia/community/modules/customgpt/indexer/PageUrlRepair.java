package org.jahia.community.modules.customgpt.indexer;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.lang.reflect.InvocationTargetException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private static final String NULL_SEGMENT = "null";
    /** Above this share of examined pages, a rewrite plan is treated as a bug rather than a mass move. */
    private static final int MAX_REWRITE_PERCENT = 25;
    /** At roughly 0.6s per page, the most that comfortably finishes inside one HTTP request. */
    private static final int MAX_SYNCHRONOUS_PAGES = 100;

    private final OkHttpClient customGptClient;
    private final String projectId;
    private final String apiBaseUrl;

    private int repaired;
    private int intact;
    private int failed;
    private int dangling;

    public PageUrlRepair(OkHttpClient customGptClient, String projectId, String apiBaseUrl) {
        this.customGptClient = customGptClient;
        this.projectId = projectId;
        this.apiBaseUrl = apiBaseUrl;
    }

    /** @return the number of pages whose URL was repaired */
    /**
     * Examines every indexed page for the site and, unless {@code dryRun}, rewrites the URLs that need it.
     *
     * <p>Two phases on purpose. The examine phase writes nothing, so a dry run reports exactly what a real run
     * would do; and the plan is sized before anything is written, which is what stops a systematically wrong
     * computation from rewriting the whole corpus.
     *
     * @return the number of pages whose URL was rewritten, or would be on a real run
     */
    public int repairSite(String siteKey, Collection<String> pageIds, boolean dryRun)
            throws RepositoryException, IOException {
        final JahiaUser rootUser = JahiaUserManagerService.getInstance().lookupRootUser().getJahiaUser();
        // The locale is load-bearing. getUrl() renders the session locale into the path, so opening the session
        // without one produced "null" as a path segment and wrote a 404 over every URL it touched in production.
        final Locale siteLocale = resolveSiteLocale(siteKey);
        final Map<String, String> mappings = restrictTo(collectPageMappings(siteKey), pageIds);
        assertRunIsBounded(mappings.size());
        LOGGER.info("[repairPageUrls] {} indexed page(s) to examine for site {} (locale {}){}",
                mappings.size(), siteKey, siteLocale.toLanguageTag(), dryRun ? ", dry run" : "");

        final List<PlannedRewrite> plan = new ArrayList<>();
        for (Map.Entry<String, String> entry : mappings.entrySet()) {
            examineOnePage(entry.getKey(), entry.getValue(), rootUser, siteLocale, plan);
        }

        LOGGER.info("[repairPageUrls] {} examined, {} to rewrite, {} already correct, {} pointing at a page the"
                + " project no longer holds, {} could not be examined",
                mappings.size(), plan.size(), intact, dangling, failed);
        for (PlannedRewrite rewrite : plan) {
            LOGGER.info("[repairPageUrls] {} page {} ({}): {} -> {}", dryRun ? "Would rewrite" : "Rewriting",
                    rewrite.pageId, rewrite.nodePath, rewrite.storedUrl, rewrite.page.url);
        }
        if (dryRun) {
            return plan.size();
        }
        assertPlanIsPlausible(plan.size(), mappings.size() - dangling);

        for (PlannedRewrite rewrite : plan) {
            writeOneRewrite(rewrite);
        }
        LOGGER.info("[repairPageUrls] Complete for site {} - {} of {} planned rewrite(s) applied",
                siteKey, repaired, plan.size());
        return repaired;
    }

    /** Narrows the mapping nodes to the given CustomGPT page ids, or keeps them all when none are given. */
    private static Map<String, String> restrictTo(Map<String, String> mappings, Collection<String> pageIds) {
        if (pageIds == null || pageIds.isEmpty()) {
            return mappings;
        }
        final Set<String> wanted = new HashSet<>(pageIds);
        final Map<String, String> restricted = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : mappings.entrySet()) {
            if (wanted.contains(entry.getValue())) {
                restricted.put(entry.getKey(), entry.getValue());
            }
        }
        return restricted;
    }

    /**
     * Refuses a run too large to finish inside an HTTP request.
     *
     * <p>This runs synchronously on the caller's request thread at roughly 0.6s per page, so a whole-site run over
     * thousands of mapping nodes takes half an hour and no client survives it. That is not merely a timeout: the
     * client gives up while the SERVER KEEPS WRITING, so a real run would rewrite an unknown subset with no
     * summary and no returned count. Refusing to start is strictly better than finishing invisibly.
     *
     * <p>Scope a large job with explicit page ids, in batches, until this runs as a background job.
     */
    static void assertRunIsBounded(int toExamine) throws IOException {
        if (toExamine > MAX_SYNCHRONOUS_PAGES) {
            throw new IOException("Refusing to examine " + toExamine + " pages in one request (limit "
                    + MAX_SYNCHRONOUS_PAGES + "). This runs synchronously and would outlive the request, leaving"
                    + " an unknown subset rewritten with no summary. Pass explicit pageIds to scope the run.");
        }
    }

    /**
     * Refuses to apply a plan that rewrites an implausible share of the corpus.
     *
     * <p>The per-URL guard catches a computation that produces an obviously broken URL. This catches the other
     * shape: one that produces a <em>plausible</em> but wrong URL for everything, which no per-URL check can
     * distinguish from a genuine mass move. Expected real-world scope is a few percent.
     */
    static void assertPlanIsPlausible(int toRewrite, int examined) throws IOException {
        if (examined > 0 && toRewrite * 100L > (long) examined * MAX_REWRITE_PERCENT) {
            throw new IOException("Refusing to rewrite " + toRewrite + " of " + examined + " page URLs ("
                    + (toRewrite * 100 / examined) + "%, limit " + MAX_REWRITE_PERCENT + "%). A share this large"
                    + " is far more likely to mean the URLs are being computed wrongly than that the site moved."
                    + " Run with dryRun to inspect the planned changes.");
        }
    }

    /** The site's default language; without it {@code getUrl()} cannot produce a usable path. */
    private static Locale resolveSiteLocale(String siteKey) throws RepositoryException {
        final String language = JCRTemplate.getInstance().doExecuteWithSystemSession(session ->
                session.getNode(CustomGptConstants.PATH_SITES + siteKey).getResolveSite().getDefaultLanguage());
        if (language == null || language.trim().isEmpty()) {
            throw new RepositoryException("Site " + siteKey + " has no default language, so page URLs cannot be"
                    + " resolved. Refusing rather than writing URLs with an unresolved language segment.");
        }
        return Locale.forLanguageTag(language);
    }

    private static final class PlannedRewrite {
        private final String nodePath;
        private final String pageId;
        private final String storedUrl;
        private final PageToRepair page;

        private PlannedRewrite(String nodePath, String pageId, String storedUrl, PageToRepair page) {
            this.nodePath = nodePath;
            this.pageId = pageId;
            this.storedUrl = storedUrl;
            this.page = page;
        }
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

    /**
     * Refuses a computed URL that is obviously wrong, BEFORE it overwrites a good one.
     *
     * <p>The read-back verification cannot help here and never could: it proves the value was stored, not that it
     * is right. Writing a corrupt URL and reading the same corrupt URL back passes the id and url checks
     * perfectly. Correctness has to be judged before the write.
     *
     * <p>Written after this repair wrote {@code https://<host>/null/<jcr-path>.html} over correct URLs in
     * production, because the JCR session was opened with a null locale and {@code getUrl()} rendered that locale
     * into the path as the literal string "null". Every page it touched became a 404.
     */
    static void assertUrlUsable(String computedUrl, String storedUrl) throws IOException {
        if (computedUrl == null || computedUrl.trim().isEmpty()) {
            throw new IOException("Refusing to write an empty URL");
        }
        final URI computed;
        try {
            computed = new URI(computedUrl);
        } catch (URISyntaxException e) {
            throw new IOException("Refusing to write an unparseable URL: " + computedUrl, e);
        }
        if (computed.getHost() == null) {
            throw new IOException("Refusing to write a URL with no host: " + computedUrl);
        }
        for (String segment : computed.getPath().split("/")) {
            if (NULL_SEGMENT.equals(segment)) {
                // A whole segment equal to "null" is a Java null concatenated into the path, never a real page.
                throw new IOException("Refusing to write a URL containing a \"null\" path segment,"
                        + " which means a value could not be resolved: " + computedUrl);
            }
        }
        if (storedUrl == null) {
            return;
        }
        final String storedHost = hostOf(storedUrl);
        if (storedHost != null && !storedHost.equals(computed.getHost())) {
            // A move changes the path, not the host. Disagreeing on the host is far more likely to be a bug.
            throw new IOException("Refusing to move a page to a different host: stored " + storedHost
                    + ", computed " + computed.getHost() + " (" + computedUrl + ")");
        }
    }

    private static String hostOf(String url) {
        try {
            return new URI(url).getHost();
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private void examineOnePage(String nodePath, String pageId, JahiaUser rootUser, Locale siteLocale,
            List<PlannedRewrite> plan) {
        try {
            // Read more than once before believing a missing URL: this API returns a correct envelope with a
            // dropped payload often enough that a single read manufactures rewrite targets for correct pages.
            final String stored = CustomGptIndexerNodeHandler.readStoredUrl(customGptClient, projectId,
                    pageId, apiBaseUrl);
            // readPage throws when the node is gone, which counts the page as unexaminable and names it in the
            // log. That is the right outcome: a page whose node no longer exists needs deleting from the corpus,
            // not a rewritten URL, and this makes those visible instead of silently passing as intact.
            final PageToRepair page = readPage(nodePath, rootUser, siteLocale);
            if (!needsRepair(stored, page.url)) {
                intact++;
                return;
            }
            // Judged BEFORE anything is written. The read-back proves a value was stored, never that it is right.
            assertUrlUsable(page.url, stored);
            plan.add(new PlannedRewrite(nodePath, pageId, stored, page));
        } catch (CustomGptIndexerNodeHandler.PageGoneException e) {
            // Not a failure and not actionable: the page was removed from the project out of band, leaving this
            // mapping node behind. It self-heals on the node's next publication. Counted apart so a run is not
            // buried under thousands of entries that need no action.
            dangling++;
            LOGGER.debug("[repairPageUrls] Mapping node {} points at page {}, which the project no longer holds",
                    nodePath, pageId);
        } catch (RepositoryException | IOException | RuntimeException e) {
            failed++;
            LOGGER.warn("[repairPageUrls] Could not examine page {} ({})", pageId, nodePath, e);
        }
    }

    private void writeOneRewrite(PlannedRewrite rewrite) {
        try {
            // Goes through the same checked write as indexation, so the repair is itself verified by read-back
            // rather than trusting the 2xx that caused this state in the first place.
            CustomGptIndexerNodeHandler.updatePageMetadataChecked(customGptClient, projectId, rewrite.pageId,
                    rewrite.page.title, rewrite.page.url, apiBaseUrl);
            repaired++;
        } catch (IOException | RuntimeException e) {
            failed++;
            LOGGER.warn("[repairPageUrls] Could not rewrite the URL of page {} ({})",
                    rewrite.pageId, rewrite.nodePath, e);
        }
    }

    /** The title and public URL to write back, read from the live workspace in one session. */
    private static PageToRepair readPage(String nodePath, JahiaUser rootUser, Locale siteLocale)
            throws RepositoryException {
        return JCRTemplate.getInstance().doExecuteWithSystemSessionAsUser(rootUser, Constants.LIVE_WORKSPACE, siteLocale,
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
