package org.jahia.community.modules.customgpt.indexer;

import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import javax.jcr.RepositoryException;
import javax.servlet.ServletException;
import okhttp3.Credentials;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.commons.lang.StringUtils;
import org.jahia.api.Constants;
import org.jahia.community.modules.customgpt.CustomGptConstants;
import org.jahia.community.modules.customgpt.CustomGptRequest;
import org.jahia.community.modules.customgpt.DeleteRequest;
import org.jahia.community.modules.customgpt.IndexRequest;
import org.jahia.community.modules.customgpt.service.Service;
import org.jahia.community.modules.customgpt.settings.Config;
import org.jahia.community.modules.customgpt.settings.NotConfiguredException;
import org.jahia.community.modules.customgpt.util.HttpServletRequestMock;
import org.jahia.community.modules.customgpt.util.HttpServletResponseMock;
import org.jahia.community.modules.customgpt.util.SecurityUtils;
import org.jahia.community.modules.customgpt.util.Utils;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.jahia.services.render.RenderContext;
import org.jahia.services.sites.SitesSettings;
import org.jahia.services.usermanager.JahiaUser;
import org.jahia.services.usermanager.JahiaUserManagerService;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Internal utility that executes the actual HTTP interactions for one indexing cycle.
 * For each node to index it follows a three-step flow:
 * 1. Render the Jahia page HTML via {@code jahiaClient} (with Basic auth or optional cookie).
 * 2. POST the HTML as a multipart upload to {@code POST /projects/{id}/sources} to create a CustomGPT page.
 * 3. PATCH the returned page's metadata (title + canonical URL) via {@code PUT .../pages/{pageId}/metadata}.
 * The CustomGPT page ID is persisted on a {@code jnt:customGptIndexEntry} child node so that subsequent
 * updates can delete the old page before posting a new one.
 */
final class CustomGptIndexerNodeHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(CustomGptIndexerNodeHandler.class);
    private static final String HEADER_ACCEPT = "accept";
    private static final String HEADER_USER_AGENT = "User-Agent";
    private static final String HEADER_CONTENT_TYPE = "content-type";
    private static final String MEDIA_TYPE_JSON = "application/json";
    private static final String VALUE_FALSE = "false";
    private static final long RETRY_DELAY_MS = 500L;
    private static final String PROP_URL = "url";
    private static final String HEADER_LOCATION = "Location";
    /** Enough for Jahia's canonicalisation hops; a longer chain is a loop or a misconfiguration. */
    private static final int MAX_REDIRECT_HOPS = 3;
    private static final int HTTP_FORBIDDEN = 403;
    private static final int HTTP_NOT_FOUND = 404;
    /** How many times a missing URL is re-read before it is believed missing. */
    private static final int URL_READ_ATTEMPTS = 2;

    private CustomGptIndexerNodeHandler() {
        throw new IllegalStateException("Utility class");
    }

    static void handleNodeToReindex(OkHttpClient customGptClient, OkHttpClient jahiaClient, Indexer customGptIndexer) throws RepositoryException, NotConfiguredException, IOException {
        LOGGER.debug("Starting to handle nodes to reindex");
        Set<CustomGptRequest> requests = new LinkedHashSet<>();
        final JCRSessionWrapper systemSession = customGptIndexer.getSystemSession();
        final Set<String> paths = new TreeSet<>();
        paths.addAll(customGptIndexer.getNodePathsToAddOrReIndex());
        paths.addAll(customGptIndexer.getNodePathsToRemove());
        for (String path : paths) {
            try {
                final JCRNodeWrapper node;
                if (systemSession.nodeExists(path)) {
                    node = systemSession.getNode(path);
                } else {
                    node = Utils.getParentOfType(systemSession, customGptIndexer.getCustomGptConfig(), path);
                }
                addIndexRequests(node, customGptIndexer, requests);

            } catch (RepositoryException e) {
                LOGGER.warn("Cannot index node : {}, {}", path, e.getMessage());
            }
        }
        for (String customGptPageToRemove : customGptIndexer.getCustomGptPageToRemove()) {
            delete(customGptClient, customGptPageToRemove, customGptIndexer);
        }

        for (CustomGptRequest request : requests) {
            if (request instanceof IndexRequest) {
                final IndexRequest createCustomGptRequest = ((IndexRequest) request);
                index(customGptClient, jahiaClient, createCustomGptRequest, customGptIndexer);
            } else if (request instanceof DeleteRequest) {
                final DeleteRequest deleteCustomGptRequest = ((DeleteRequest) request);
                LOGGER.info("Deleting: {} in {}", deleteCustomGptRequest.getNode().getPath(), deleteCustomGptRequest.getLanguage());
            }
        }
        systemSession.refresh(false);
        LOGGER.debug("Ending to handle nodes to reindex");
    }

    private static void delete(OkHttpClient customGptClient, String pageId, Indexer customGptIndexer) throws IOException {
        String apiBaseUrl = getApiBaseUrl(customGptIndexer);
        final int status = deleteCustomGptPage(customGptClient,
                customGptIndexer.getCustomGptConfig().getCustomGptProjectId(), pageId, apiBaseUrl);
        if (!isPageGone(status)) {
            // Nothing is added in this path, so there is no duplicate to prevent - but a page the module believes
            // it deleted and which is still answering queries needs to be visible rather than silently assumed.
            LOGGER.error("CustomGPT page {} was queued for removal but could not be deleted (HTTP {}). It may still"
                    + " be present in the project and retrievable by the chatbot.", pageId, status);
        }
    }

    private static void index(OkHttpClient customGptClient, OkHttpClient jahiaClient, IndexRequest createCustomGptRequest, Indexer customGptIndexer) throws RepositoryException {
        final String apiBaseUrl = getApiBaseUrl(customGptIndexer);
        final JCRNodeWrapper nodeToIndex = createCustomGptRequest.getNode();
        final JCRSiteNode siteNode = nodeToIndex.getResolveSite();
        final String language = createCustomGptRequest.getLanguage();
        final JahiaUser rootUser = JahiaUserManagerService.getInstance().lookupRootUser().getJahiaUser();
        JCRTemplate.getInstance().doExecuteWithSystemSessionAsUser(rootUser, Constants.LIVE_WORKSPACE, language == null ? null : Locale.forLanguageTag(language), session -> {
            indexInSession(session, customGptClient, jahiaClient, nodeToIndex, siteNode, language, apiBaseUrl, customGptIndexer, rootUser);
            return null;
        });
    }

    @SuppressWarnings("java:S107")
    private static void indexInSession(JCRSessionWrapper session, OkHttpClient customGptClient, OkHttpClient jahiaClient,
            JCRNodeWrapper nodeToIndex, JCRSiteNode siteNode, String language, String apiBaseUrl,
            Indexer customGptIndexer, JahiaUser rootUser) throws RepositoryException {
        if (!session.nodeExists(nodeToIndex.getPath())) {
            LOGGER.warn("Skipping indexation of {}: it does not exist in the live workspace", nodeToIndex.getPath());
            return;
        }
        if (customGptIndexer.getCustomGptConfig().isDryRun()) {
            // Say so. This used to return silently, which is indistinguishable from indexing that simply never
            // happened - and dryRun defaults to true in the shipped configuration, so it is the likeliest reason
            // for "the module is running but nothing is indexed".
            LOGGER.info("Dry run is enabled: not indexing {} in CustomGPT. Set dryRun=false to index for real.",
                    nodeToIndex.getPath());
            return;
        }
        try {
            final JCRNodeWrapper liveNode = session.getNode(nodeToIndex.getPath());
            // Raises rather than returning when the site's sitemapIndexURL is missing or malformed. That used to
            // be two silent early returns, which meant a site with a broken sitemapIndexURL indexed nothing at
            // all and still reported success - the same silent-success shape as the skipped render below.
            final String url = resolvePublicUrl(liveNode, siteNode, rootUser, customGptIndexer.getCustomGptConfig());
            removeExistingPage(customGptClient, customGptIndexer, apiBaseUrl, rootUser, liveNode.getPath(), url, language);
            indexJahiaPage(customGptClient, jahiaClient, customGptIndexer, apiBaseUrl, rootUser, liveNode, url, language);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            customGptIndexer.recordFailure(nodeToIndex.getPath(), language, ex);
        } catch (RepositoryException | IOException | ServletException | InvocationTargetException | URISyntaxException ex) {
            // Recorded, not just logged: this used to be a bare "Issue:" with no node path, and because it was
            // swallowed here the surrounding run still completed normally and the site was reported as indexed.
            customGptIndexer.recordFailure(nodeToIndex.getPath(), language, ex);
        }
    }

    /**
     * The public URL this module indexes {@code liveNode} under: the site's server name plus the node's
     * outbound-rewritten path, with the thread user bound so vanity URLs resolve.
     *
     * <p>Shared by the indexation path and the URL repair pass, so a repaired URL is by construction the same URL
     * indexation would have produced. That sharing is why {@code customGptConfig} is threaded all the way here
     * rather than read on the indexation path alone: were the configured server-name override applied only when
     * indexing, the repair pass would recompute every URL against the sitemap host, judge all of them stale, and
     * rewrite the whole corpus back.
     *
     * @param customGptConfig supplies the optional server-name override; null means "use the sitemapIndexURL host"
     */
    static String resolvePublicUrl(JCRNodeWrapper liveNode, JCRSiteNode siteNode, JahiaUser rootUser,
            Config customGptConfig)
            throws IOException, ServletException, InvocationTargetException, URISyntaxException {
        final String hostName = Utils.getHostName(siteNode, customGptConfig);
        if (StringUtils.isEmpty(hostName)) {
            throw new IOException("No server name for site " + siteNode.getName()
                    + ": no serverName is configured for it and its sitemapIndexURL property yields no usable host");
        }
        final URL serverUrl;
        try {
            serverUrl = URI.create(hostName).toURL();
        } catch (MalformedURLException | IllegalArgumentException e) {
            throw new IOException("The server name resolved for site " + siteNode.getName()
                    + " does not match a URL pattern", e);
        }
        return hostName + Utils.encode(liveNode.getUrl(), buildRenderContext(serverUrl, siteNode, rootUser), rootUser);
    }

    private static RenderContext buildRenderContext(URL serverUrl, JCRSiteNode siteNode, JahiaUser rootUser) {
        final HttpServletRequestMock request = new HttpServletRequestMock(new HashMap<>(), serverUrl.getHost(), serverUrl.getPath());
        final HttpServletResponseMock response = new HttpServletResponseMock(new StringWriter());
        final RenderContext customRenderContext = new RenderContext(request, response, rootUser);
        customRenderContext.setSite(siteNode);
        return customRenderContext;
    }

    static void removeExistingPage(OkHttpClient customGptClient, Indexer customGptIndexer, String apiBaseUrl,
            JahiaUser rootUser, String nodePath, String url, String language) throws RepositoryException, IOException {
        final String existingPageId = getExistingPageId(rootUser, nodePath);
        if (existingPageId == null) {
            return;
        }
        LOGGER.info("Removing page with the id {} for the url {}, language {}", existingPageId, url, language);
        final int status = deleteCustomGptPage(customGptClient,
                customGptIndexer.getCustomGptConfig().getCustomGptProjectId(), existingPageId, apiBaseUrl);
        if (!isPageGone(status)) {
            // Abort rather than add a replacement. The previous page may still be in the project, and adding
            // another copy is precisely how re-publishing an already-indexed page accumulated duplicates: this
            // status used to be discarded, so a failed delete was followed by an unconditional add, silently.
            // Aborting leaves the page's content stale until the next publication, which is recoverable;
            // a duplicate is not, short of an out-of-band purge.
            throw new IOException("Could not remove the previous CustomGPT page " + existingPageId + " for " + url
                    + " (HTTP " + status + "). Not adding a replacement, because that would leave the previous"
                    + " page in the project as a duplicate. This node will be retried on its next publication.");
        }
    }

    @SuppressWarnings("java:S107")
    static void indexJahiaPage(OkHttpClient customGptClient, OkHttpClient jahiaClient, Indexer customGptIndexer,
            String apiBaseUrl, JahiaUser rootUser, JCRNodeWrapper liveNode, String url, String language)
            throws RepositoryException, IOException, InterruptedException {
        LOGGER.debug("Adding url {}", url);
        try (Response jahiaResponse = getJahiaPageContent(jahiaClient, url, customGptIndexer.getCustomGptConfig())) {
            if (jahiaResponse == null || !jahiaResponse.isSuccessful()) {
                // Raised, not logged-and-skipped. Returning here left the enclosing run reporting success over a
                // page that was never uploaded, which is how the vanity-URL defect stayed invisible: the raw .html
                // path answers 302, this module does not follow redirects, and nothing counted a failure.
                throw new IOException("Impossible to retrieve content from " + url
                        + (jahiaResponse == null ? " (no response)" : " (HTTP " + jahiaResponse.code() + ")"));
            }
            LOGGER.debug("Retrieve Jahia page content is successful for {}", url);
            if (jahiaResponse.body() == null) {
                throw new IOException("Jahia returned an empty body for " + url);
            }
            final String output = jahiaResponse.body().string();
            final String title = liveNode.hasProperty(Constants.JCR_TITLE)
                    ? liveNode.getPropertyAsString(Constants.JCR_TITLE)
                    : liveNode.getName();
            // The URL actually fetched, after any same-origin redirect. Storing the pre-redirect one would give
            // the reader a citation that costs them a hop, and is how raw .html paths came to sit in the corpus.
            final String finalUrl = jahiaResponse.request().url().toString();
            if (!finalUrl.equals(url)) {
                LOGGER.info("Indexing {} under its canonical URL {} rather than {}", liveNode.getPath(), finalUrl, url);
            }
            uploadAndUpdateMetadata(customGptClient, customGptIndexer, apiBaseUrl, rootUser, liveNode, title, output, finalUrl, language);
        }
    }

    @SuppressWarnings("java:S107")
    private static void uploadAndUpdateMetadata(OkHttpClient customGptClient, Indexer customGptIndexer, String apiBaseUrl,
            JahiaUser rootUser, JCRNodeWrapper liveNode, String title, String output, String url, String language)
            throws IOException, RepositoryException {
        final String projectId = customGptIndexer.getCustomGptConfig().getCustomGptProjectId();
        LOGGER.debug("Adding page in customGPT for {}", url);
        try (Response addDocResponse = addPage(customGptClient, projectId, title, output, apiBaseUrl)) {
            if (!addDocResponse.isSuccessful()) {
                throw new IOException("Impossible to add the page for the URL " + url + ", following response received, " + addDocResponse);
            }
            if (addDocResponse.body() == null) {
                throw new IOException("Empty response body when adding the page for the URL " + url);
            }
            LOGGER.debug("Adding page in customGPT is successful, retrieving response body");
            final JSONObject document = new JSONObject(addDocResponse.body().string());
            final String pageId = extractPageId(document);
            LOGGER.debug("Writing page id {} to mapping node for {}, language {}", pageId, liveNode.getPath(), language);
            writeMappingNode(rootUser, liveNode.getPath(), pageId);
            updatePageMetadataChecked(customGptClient, projectId, pageId, title, url, apiBaseUrl);
        }
    }

    /**
     * Writes the page metadata and reads it back, retrying until the stored URL matches what was sent.
     *
     * <p>A 2xx on the PUT is not evidence the write took. Under sustained bulk load this API answers
     * {@code status: success} with an empty body while reporting zero errors, and the write is simply dropped.
     * 47 of 1920 pages in the production corpus carry no URL because of it - 47 citations a user cannot click -
     * and because the PUT "succeeded" nothing was ever logged.
     *
     * <p>The read-back checks {@code data.id} as well as {@code data.url}. A response that does not name the page
     * that was asked for is the exact signature measured at high concurrency, and comparing only the URL would
     * accept another page's metadata as proof of this page's write.
     */
    static void updatePageMetadataChecked(OkHttpClient customGptClient, String projectId, String pageId,
            String title, String url, String apiBaseUrl) throws IOException {
        IOException lastMismatch = null;
        for (int attempt = 1; attempt <= CustomGptConstants.MAX_RETRIES; attempt++) {
            putPageMetadata(customGptClient, projectId, pageId, title, url, apiBaseUrl);
            try {
                verifyStoredMetadata(customGptClient, projectId, pageId, url, apiBaseUrl);
                return;
            } catch (MetadataNotStoredException e) {
                lastMismatch = new IOException(e.getMessage());
                LOGGER.warn("CustomGPT accepted the metadata write for page {} but did not store it"
                        + " (attempt {}/{}): {}", pageId, attempt, CustomGptConstants.MAX_RETRIES, e.getMessage());
                if (attempt < CustomGptConstants.MAX_RETRIES) {
                    sleepBeforeRetry();
                }
            }
        }
        throw lastMismatch;
    }

    private static void sleepBeforeRetry() throws IOException {
        try {
            Thread.sleep(RETRY_DELAY_MS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while retrying a CustomGPT metadata write", ie);
        }
    }

    /** Raised when the API accepted a metadata write but did not store it; retryable, unlike a rejected write. */
    private static class MetadataNotStoredException extends Exception {
        MetadataNotStoredException(String message) {
            super(message);
        }
    }

    private static void putPageMetadata(OkHttpClient customGptClient, String projectId, String pageId,
            String title, String url, String apiBaseUrl) throws IOException {
        LOGGER.debug("Updating page metadata in customGPT");
        try (Response metaResponse = updatePageMedata(customGptClient, projectId, pageId, title, url, apiBaseUrl)) {
            if (!metaResponse.isSuccessful()) {
                // Include the body: a bare status line (a 422 in particular) says nothing about which field the
                // API rejected, and this runs after the page and its mapping node already exist, so the page is
                // indexed with stale metadata rather than missing - worth diagnosing, not just counting.
                final String body = metaResponse.body() == null ? "<no body>" : metaResponse.body().string();
                throw new IOException("Failed to update CustomGPT page metadata for page " + pageId
                        + " (" + url + "): HTTP " + metaResponse.code() + ", " + body);
            }
        }
    }

    /** Reads the stored metadata back, raising when it does not carry the URL that was just written. */
    private static void verifyStoredMetadata(OkHttpClient customGptClient, String projectId, String pageId,
            String url, String apiBaseUrl) throws IOException, MetadataNotStoredException {
        final String storedUrl = readStoredUrl(customGptClient, projectId, pageId, apiBaseUrl);
        if (!url.equals(storedUrl)) {
            throw new MetadataNotStoredException("page " + pageId + " stores url " + storedUrl + ", expected " + url);
        }
    }

    /**
     * The URL stored for a page, read more than once before a missing value is believed.
     *
     * <p>A single read is not enough, and the {@code data.id} echo does not make it enough. This API was observed
     * returning {@code {"status":"success","data":{"id":<the right id>,...,"url":null}}} for a page confirmed by a
     * direct call to have a URL: correct envelope, dropped payload.
     *
     * <p>The degradation drops values rather than inventing them, so corroboration is asymmetric - a value seen in
     * ANY read is real, and only a value absent from every read is believed absent. Re-reads are spaced, because
     * a second sample taken in the same burst is not an independent one. Without this, a dropped read
     * manufactures a rewrite target for a page that was already correct in the examine phase, and makes a
     * successful write look like a failure in the verification phase.
     *
     * @return the stored URL, or {@code null} when no read returned one
     */
    static String readStoredUrl(OkHttpClient customGptClient, String projectId, String pageId, String apiBaseUrl)
            throws IOException {
        for (int attempt = 1; attempt <= URL_READ_ATTEMPTS; attempt++) {
            if (attempt > 1) {
                // Space the re-read from the read it is corroborating. The drop this guards against is
                // sustained-volume dependent - 6 concurrent workers produced 2558 false nulls where 2 produced
                // none - so a retry issued in the same burst inherits the same cause and corroborates nothing.
                // The decorrelating variable is time, not attempt count.
                sleepBeforeRetry();
            }
            final JSONObject data = fetchPageMetadata(customGptClient, projectId, pageId, apiBaseUrl);
            if (data == null) {
                continue;
            }
            final String echoedId = data.optString("id", null);
            if (echoedId == null || !echoedId.equals(pageId)) {
                // Not a dropped value but a response about something else; corroboration must not paper over it.
                throw new IOException("Read-back of page " + pageId + " named page " + echoedId);
            }
            if (!data.isNull(PROP_URL)) {
                final String url = data.optString(PROP_URL, null);
                if (url != null && !url.trim().isEmpty()) {
                    return url;
                }
            }
        }
        return null;
    }

    /** {@code GET /projects/{projectId}/pages/{pageId}/metadata}, returning its {@code data} object. */
    static JSONObject fetchPageMetadata(OkHttpClient customGptClient, String projectId, String pageId,
            String apiBaseUrl) throws IOException {
        final Request request = new Request.Builder()
                .url(String.format("%s/projects/%s/pages/%s/metadata", apiBaseUrl, projectId, pageId))
                .get()
                .addHeader(HEADER_ACCEPT, MEDIA_TYPE_JSON)
                .build();
        try (Response response = customGptClient.newCall(request).execute()) {
            if (response.code() == HTTP_FORBIDDEN || response.code() == HTTP_NOT_FOUND) {
                throw new PageGoneException(pageId, response.code());
            }
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Could not read back CustomGPT metadata for page " + pageId
                        + ": HTTP " + response.code());
            }
            return new JSONObject(response.body().string()).optJSONObject("data");
        }
    }

    /**
     * Raised when the project no longer holds the page: a mapping node pointing at something that is gone.
     *
     * <p>Distinct from an ordinary failure because it is neither actionable nor a defect. Pages get removed from
     * a project directly through the API - an out-of-band cleanup of duplicates or obsolete content - without
     * Jahia being told, which leaves the mapping node behind. That self-heals on the node's next publication, and
     * since a delete now treats an absent page as gone, it no longer orphans a replacement.
     *
     * <p>It extends {@link IOException} so callers that do not care keep treating it as a failure; the URL repair
     * counts it separately so a run is not buried under thousands of entries that need no action.
     */
    static class PageGoneException extends IOException {

        private static final long serialVersionUID = 1L;

        PageGoneException(String pageId, int status) {
            super("CustomGPT no longer holds page " + pageId + " (HTTP " + status + ")");
        }
    }

    private static String getExistingPageId(JahiaUser rootUser, String nodePath) throws RepositoryException {
        return JCRTemplate.getInstance().doExecuteWithSystemSessionAsUser(rootUser, Constants.EDIT_WORKSPACE, null, session -> {
            final String mappingPath = CustomGptConstants.buildMappingPath(nodePath);
            if (session.nodeExists(mappingPath)) {
                final JCRNodeWrapper node = session.getNode(mappingPath);
                if (node.hasProperty(CustomGptConstants.PROP_CUSTOM_GPT_PAGE_ID)) {
                    return node.getProperty(CustomGptConstants.PROP_CUSTOM_GPT_PAGE_ID).getString();
                }
            }
            return null;
        });
    }

    private static void writeMappingNode(JahiaUser rootUser, String nodePath, String pageId) throws RepositoryException {
        JCRTemplate.getInstance().doExecuteWithSystemSessionAsUser(rootUser, Constants.EDIT_WORKSPACE, null, session -> {
            final JCRNodeWrapper mappingNode = getOrCreateMappingNode(session, nodePath);
            mappingNode.setProperty(CustomGptConstants.PROP_CUSTOM_GPT_PAGE_ID, pageId);
            session.save();
            return null;
        });
    }

    private static JCRNodeWrapper getOrCreateMappingNode(JCRSessionWrapper session, String nodePath) throws RepositoryException {
        final String mappingPath = CustomGptConstants.buildMappingPath(nodePath);
        if (session.nodeExists(mappingPath)) {
            return session.getNode(mappingPath);
        }
        final JCRNodeWrapper parentNode = session.getNode(nodePath);
        if (!parentNode.isNodeType(CustomGptConstants.MIX_CUSTOM_GPT_INDEXABLE)) {
            parentNode.addMixin(CustomGptConstants.MIX_CUSTOM_GPT_INDEXABLE);
        }
        return parentNode.addNode(CustomGptConstants.CUSTOMGPT_INDEX_NODE_NAME, CustomGptConstants.NT_CUSTOM_GPT_INDEX_ENTRY);
    }

    /**
     * Deletes a CustomGPT page and returns the HTTP status.
     *
     * <p>Returns the status rather than a boolean deliberately. The caller has to distinguish "the page is already
     * gone" from "the page may still be there", and those are different statuses; and the status has to reach the
     * log, because the CustomGPT API is known to degrade under sustained volume (thousands of requests, not mere
     * concurrency). If a degraded response ever starts reading as a success, the only way that becomes visible
     * without auditing the corpus is if the status of every non-trivial outcome was logged at the time.
     */
    private static int deleteCustomGptPage(OkHttpClient customGptClient, String customGptProject, String pageId, String apiBaseUrl) throws IOException {
        LOGGER.info("Removing page with the id {}", pageId);
        final Request delPageRequest = new Request.Builder()
                .url(String.format("%s/projects/%s/pages/%s", apiBaseUrl, customGptProject, pageId))
                .delete()
                .addHeader(HEADER_ACCEPT, MEDIA_TYPE_JSON)
                .build();
        try (Response delPageResponse = customGptClient.newCall(delPageRequest).execute()) {
            if (!delPageResponse.isSuccessful()) {
                LOGGER.warn("DELETE of CustomGPT page {} returned HTTP {}: {}", pageId, delPageResponse.code(),
                        describeBody(delPageResponse));
            }
            return delPageResponse.code();
        }
    }

    /** First 200 characters of a response body, for diagnostics; never throws. */
    private static String describeBody(Response response) {
        try {
            if (response.body() == null) {
                return "<no body>";
            }
            final String body = response.body().string();
            return body.length() > 200 ? body.substring(0, 200) + "..." : body;
        } catch (IOException e) {
            return "<unreadable body: " + e.getMessage() + ">";
        }
    }

    /**
     * Whether a DELETE status means the page is definitely no longer in the project.
     *
     * <p>{@code 2xx} is an actual deletion. {@code 403} is what this API returns for a page id that no longer
     * exists - measured, not assumed: it answers {@code 403 {"message": "This action is unauthorized."}} rather
     * than {@code 404}, consistently. A stale id in the sidecar is the common case and it leaves nothing behind,
     * so it must not block re-indexing.
     *
     * <p>Every other status - notably {@code 429} and {@code 5xx} - means the page may well still be there. The
     * caller must NOT add a replacement in that case: doing so is what leaves the old page in the project as a
     * duplicate, which is the defect this method exists to prevent.
     */
    static boolean isPageGone(int deleteStatus) {
        return (deleteStatus >= 200 && deleteStatus < 300) || deleteStatus == 403;
    }

    private static Response addPage(OkHttpClient customGptClient, String customGptProject, String title, String output, String apiBaseUrl) throws IOException {
        // Build multipart body
        final MediaType mediaType = MediaType.parse("text/html");
        final RequestBody addDocBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file_data_retension", VALUE_FALSE)
                .addFormDataPart("is_ocr_enabled", VALUE_FALSE)
                .addFormDataPart("is_anonymized", VALUE_FALSE)
                .addFormDataPart("file", title,
                        RequestBody.create(output.getBytes(StandardCharsets.UTF_8), mediaType))
                .build();
        final Request request = new Request.Builder()
                .url(String.format("%s/projects/%s/sources", apiBaseUrl, customGptProject))
                .post(addDocBody)
                .addHeader(HEADER_ACCEPT, MEDIA_TYPE_JSON)
                .addHeader(HEADER_CONTENT_TYPE, "multipart/form-data")
                .build();
        return customGptClient.newCall(request).execute();
    }

    private static Response updatePageMedata(OkHttpClient customGptClient, String customGptProject, String pageId, String title, String url, String apiBaseUrl) throws IOException {
        final RequestBody metadataBody = new FormBody.Builder()
                .add("title", title)
                .add("url", url)
                .build();

        final Request request = new Request.Builder()
                .url(String.format("%s/projects/%s/pages/%s/metadata", apiBaseUrl, customGptProject, pageId))
                .put(metadataBody)
                .addHeader(HEADER_ACCEPT, MEDIA_TYPE_JSON)
                .addHeader(HEADER_CONTENT_TYPE, MEDIA_TYPE_JSON)
                .build();
        return customGptClient.newCall(request).execute();
    }

    static void addRequestsForFileOrLanguage(JCRNodeWrapper node, Service customGptService, Set<CustomGptRequest> requests, Set<String> languages) throws RepositoryException, NotConfiguredException {
        if (node.isFile()) {
            customGptService.addIndexRequests(node, null, requests);
        } else {
            for (String language : languages) {
                customGptService.addIndexRequests(node, language, requests);
            }
        }
    }

    static void addIndexRequests(JCRNodeWrapper node, Indexer customGptIndexer, Set<CustomGptRequest> requests)
            throws RepositoryException, NotConfiguredException {
        final Service customGptService = customGptIndexer.getService();
        addRequestsForFileOrLanguage(node, customGptService, requests, getLanguages(node));
    }

    static Set<String> getLanguages(JCRNodeWrapper node) throws RepositoryException {
        final JCRSiteNode site = node.getResolveSite();
        // languages contains all active EDIT languages
        final Set<String> languages = Utils.getPropertyValuesAsSet(site, SitesSettings.LANGUAGES);
        languages.removeAll(Utils.getPropertyValuesAsSet(site, SitesSettings.INACTIVE_LIVE_LANGUAGES));
        return languages;
    }

    /**
     * Fetches the rendered page, following a redirect only when it stays on the same origin.
     *
     * <p>Jahia answers the raw {@code .html} path with a 301 to its canonical form, so refusing every redirect
     * meant a first publish failed outright and the node was never indexed.
     *
     * <p>The client is deliberately built {@code .followRedirects(false)}, and that stays: this request carries
     * Basic credentials, and OkHttp would replay them to wherever the redirect pointed. Following them here
     * explicitly, and only within the same scheme, host and port, keeps the credentials on the host they were
     * issued for. A cross-origin redirect is returned unfollowed and the caller raises on it.
     */
    private static Response getJahiaPageContent(OkHttpClient jahiaClient, String url, Config config)
            throws IOException, InterruptedException {
        Response response = null;
        for (int attempts = 0; attempts < CustomGptConstants.MAX_RETRIES; attempts++) {
            response = fetchFollowingSameOriginRedirects(jahiaClient, url, config);
            if (response.isSuccessful()) {
                return response;
            }
            // A redirect that survived the hop loop is either cross-origin or past the hop limit. Either way it
            // is a routing fact rather than a blip, and retrying it only burns the retry budget.
            if (isRedirect(response.code())) {
                return response;
            }
            if (attempts < CustomGptConstants.MAX_RETRIES - 1) {
                // Close the failed response before retrying so its body/connection is not leaked; the last
                // (still unsuccessful) response is returned for the caller to inspect and close.
                response.close();
                Thread.sleep(RETRY_DELAY_MS);
            }
        }
        return response;
    }

    private static Response fetchFollowingSameOriginRedirects(OkHttpClient jahiaClient, String url, Config config)
            throws IOException {
        Response response = jahiaClient.newCall(buildRenderRequest(url, config)).execute();
        for (int hop = 1; hop <= MAX_REDIRECT_HOPS && isRedirect(response.code()); hop++) {
            final String location = response.header(HEADER_LOCATION);
            final HttpUrl next = location == null ? null : response.request().url().resolve(location);
            if (next == null || !isSameOrigin(response.request().url(), next)) {
                LOGGER.warn("Not following a redirect from {} to {}: it leaves the origin, and this request"
                        + " carries credentials that must not be replayed to another host",
                        response.request().url(), location);
                return response;
            }
            LOGGER.debug("Following same-origin redirect {} -> {}", response.request().url(), next);
            response.close();
            response = jahiaClient.newCall(buildRenderRequest(next.toString(), config)).execute();
        }
        return response;
    }

    /** Whether a redirect may be followed: identical scheme, host and port, so credentials stay put. */
    static boolean isSameOrigin(HttpUrl from, HttpUrl to) {
        return from.scheme().equals(to.scheme())
                && from.host().equals(to.host())
                && from.port() == to.port();
    }

    static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    /**
     * Builds the request that fetches a page's rendered HTML from Jahia.
     *
     * <p>Package-private so the header handling can be asserted directly; the agent and the credentials are both
     * conditional, and getting either wrong fails every page rather than one.
     */
    static Request buildRenderRequest(String url, Config config) {
        final Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .get()
                .addHeader(HEADER_CONTENT_TYPE, "text/html;charset=UTF-8");

        // Sent regardless of scheme: it carries no secret, and bot protection is as likely on an http host.
        // Left absent rather than empty when unconfigured - an empty User-Agent is itself a bot signature.
        final String userAgent = config.getUserAgent();
        if (StringUtils.isNotEmpty(userAgent)) {
            requestBuilder.header(HEADER_USER_AGENT, userAgent);
        }

        if (StringUtils.isNotEmpty(config.getJahiaUsername()) && StringUtils.isNotEmpty(config.getJahiaPassword())) {
            // Only attach Basic auth over HTTPS - the URL host comes from the site's sitemapIndexURL property,
            // which may be http://; sending the Jahia password over cleartext would expose it on the wire.
            if (SecurityUtils.isHttpsUrl(url)) {
                requestBuilder.addHeader("Authorization",
                        Credentials.basic(config.getJahiaUsername(), config.getJahiaPassword(), StandardCharsets.UTF_8));
            } else {
                LOGGER.warn("Skipping Basic authentication for non-https rendering URL to avoid sending credentials in cleartext: {}", url);
            }
        }
        return requestBuilder.build();
    }

    private static String getApiBaseUrl(Indexer customGptIndexer) {
        // Every request built from this base URL carries the CustomGPT Bearer token; the shared helper refuses a
        // non-https base URL (which could be set directly in the .cfg, bypassing the saveSettings gate).
        return SecurityUtils.resolveHttpsBaseUrl(
                customGptIndexer.getCustomGptConfig().getCustomGptApiBaseUrl(),
                CustomGptConstants.DEFAULT_CUSTOM_GPT_API_BASE_URL);
    }

    private static String extractPageId(JSONObject document) {
        if (!document.has("data")) {
            throw new IllegalArgumentException("Missing 'data' field in CustomGPT response: " + document);
        }
        JSONObject data = document.getJSONObject("data");
        if (!data.has("pages")) {
            throw new IllegalArgumentException("Missing 'pages' field in CustomGPT response data: " + data);
        }
        JSONArray pages = data.getJSONArray("pages");
        if (pages.length() == 0) {
            throw new IllegalArgumentException("Empty 'pages' array in CustomGPT response");
        }
        JSONObject firstPage = pages.getJSONObject(0);
        if (!firstPage.has("id")) {
            throw new IllegalArgumentException("Missing 'id' field in CustomGPT response page: " + firstPage);
        }
        return String.valueOf(firstPage.getLong("id"));
    }
}
