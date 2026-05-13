package org.jahia.community.modules.customgpt.indexer;

import java.io.IOException;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import javax.jcr.RepositoryException;
import okhttp3.Credentials;
import okhttp3.FormBody;
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
import org.jahia.community.modules.customgpt.util.Utils;
import org.jahia.services.content.JCRCallback;
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
                LOGGER.info("Deleting: " + deleteCustomGptRequest.getNode().getPath() + " in " + deleteCustomGptRequest.getLanguage());
            }
        }
        systemSession.refresh(false);
        LOGGER.debug("Ending to handle nodes to reindex");
    }

    private static void delete(OkHttpClient customGptClient, String pageId, Indexer customGptIndexer) throws RepositoryException, IOException {
        String apiBaseUrl = getApiBaseUrl(customGptIndexer);
        deleteCustomGptPage(customGptClient, customGptIndexer.getCustomGptConfig().getCustomGptProjectId(), pageId, apiBaseUrl);
    }

    private static void index(OkHttpClient customGptClient, OkHttpClient jahiaClient, IndexRequest createCustomGptRequest, Indexer customGptIndexer) throws RepositoryException {
        final String apiBaseUrl = getApiBaseUrl(customGptIndexer);
        final JCRNodeWrapper nodeToIndex = createCustomGptRequest.getNode();
        final JCRSiteNode siteNode = nodeToIndex.getResolveSite();
        final String language = createCustomGptRequest.getLanguage();
        final JahiaUser rootUser = JahiaUserManagerService.getInstance().lookupRootUser().getJahiaUser();
        JCRTemplate.getInstance().doExecuteWithSystemSessionAsUser(rootUser, Constants.LIVE_WORKSPACE, language == null ? null : Locale.forLanguageTag(language), new JCRCallback<Object>() {
            @Override
            public Object doInJCR(JCRSessionWrapper session) throws RepositoryException {
                final URL serverUrl;
                final String hostName = Utils.getHostName(siteNode);
                if (StringUtils.isEmpty(hostName)) {
                    LOGGER.warn("The host name can not be extracted from the property sitemapIndexURL");
                    return null;
                }

                try {
                    serverUrl = new URL(hostName);
                } catch (MalformedURLException e) {
                    LOGGER.warn("The property sitemapIndexURL does not match an URL pattern, Sitemap generation won't happen");
                    return null;
                }

                final HttpServletRequestMock request = new HttpServletRequestMock(new HashMap<>(), serverUrl.getHost(), serverUrl.getPath());
                final HttpServletResponseMock response = new HttpServletResponseMock(new StringWriter());
                final RenderContext customRenderContext = new RenderContext(request, response, rootUser);
                customRenderContext.setSite(siteNode);

                if (session.nodeExists(nodeToIndex.getPath()) && !customGptIndexer.getCustomGptConfig().isDryRun()) {
                    try {
                        final JCRNodeWrapper liveNode = session.getNode(nodeToIndex.getPath());
                        final String nodePath = liveNode.getPath();
                        final String url = hostName + Utils.encode(liveNode.getUrl(), customRenderContext);

                        final String existingPageId = getExistingPageId(rootUser, nodePath);
                        if (existingPageId != null) {
                            LOGGER.info("Removing page with the id {} for the url {}, language {}", existingPageId, url, language);
                            deleteCustomGptPage(customGptClient, customGptIndexer.getCustomGptConfig().getCustomGptProjectId(), existingPageId, apiBaseUrl);
                        }

                        LOGGER.debug("Adding url {}", url);
                        try (Response jahiaResponse = getJahiaPageContent(jahiaClient, url, customGptIndexer.getCustomGptConfig())) {
                            if (jahiaResponse != null && jahiaResponse.isSuccessful()) {
                                LOGGER.debug("Retrieve Jahia page content is successful for {}", url);
                                final String output = jahiaResponse.body().string();
                                final String title;
                                if (liveNode.hasProperty(Constants.JCR_TITLE)) {
                                    title = liveNode.getPropertyAsString(Constants.JCR_TITLE);
                                } else {
                                    title = liveNode.getName();
                                }
                                LOGGER.debug("Adding page in customGPT for {}", url);
                                try (Response addDocResponse = addPage(customGptClient, customGptIndexer.getCustomGptConfig().getCustomGptProjectId(), title, output, apiBaseUrl)) {
                                    if (addDocResponse.isSuccessful()) {
                                        LOGGER.debug("Adding page in customGPT is successful, retrieving response body");
                                        final String jsonResponse = addDocResponse.body().string();
                                        LOGGER.debug("Converting JSON response to JSON object");
                                        final JSONObject document = new JSONObject(jsonResponse);
                                        LOGGER.debug("Retrieving CustomGpt page id");
                                        final String pageId = extractPageId(document);
                                        LOGGER.debug("Writing page id {} to mapping node for {}, language {}", pageId, nodeToIndex.getPath(), language);
                                        writeMappingNode(rootUser, nodePath, pageId);
                                        LOGGER.debug("Updating page metadata in customGPT");
                                        try (Response metaResponse = updatePageMedata(customGptClient, customGptIndexer.getCustomGptConfig().getCustomGptProjectId(), pageId, title, url, apiBaseUrl)) {
                                            if (metaResponse.isSuccessful()) {
                                                LOGGER.debug("Updating page metadata in customGPT is successful");
                                            } else {
                                                throw new IOException("Unexpected code " + metaResponse);
                                            }
                                        }
                                    } else {
                                        throw new IOException("Impossible to add the page for the URL " + url + ", following response received, " + addDocResponse);
                                    }
                                }
                            } else {
                                LOGGER.warn("Impossible to retrieve content from {}", url);
                            }
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        LOGGER.error("Issue:", ex);
                    } catch (Exception ex) {
                        LOGGER.error("Issue:", ex);
                    }
                }
                return null;
            }
        });
    }

    private static String getExistingPageId(JahiaUser rootUser, String nodePath) throws RepositoryException {
        return JCRTemplate.getInstance().doExecuteWithSystemSessionAsUser(rootUser, Constants.EDIT_WORKSPACE, null, new JCRCallback<String>() {
            @Override
            public String doInJCR(JCRSessionWrapper session) throws RepositoryException {
                final String mappingPath = CustomGptConstants.buildMappingPath(nodePath);
                if (session.nodeExists(mappingPath)) {
                    final JCRNodeWrapper node = session.getNode(mappingPath);
                    if (node.hasProperty(CustomGptConstants.PROP_CUSTOM_GPT_PAGE_ID)) {
                        return node.getProperty(CustomGptConstants.PROP_CUSTOM_GPT_PAGE_ID).getString();
                    }
                }
                return null;
            }
        });
    }

    private static void writeMappingNode(JahiaUser rootUser, String nodePath, String pageId) throws RepositoryException {
        JCRTemplate.getInstance().doExecuteWithSystemSessionAsUser(rootUser, Constants.EDIT_WORKSPACE, null, new JCRCallback<Void>() {
            @Override
            public Void doInJCR(JCRSessionWrapper session) throws RepositoryException {
                final JCRNodeWrapper mappingNode = getOrCreateMappingNode(session, nodePath);
                mappingNode.setProperty(CustomGptConstants.PROP_CUSTOM_GPT_PAGE_ID, pageId);
                session.save();
                return null;
            }
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

    private static boolean deleteCustomGptPage(OkHttpClient customGptClient, String customGptProject, String pageId, String apiBaseUrl) throws IOException {
        LOGGER.info("Removing page with the id {}", pageId);
        final Request delPageRequest = new Request.Builder()
                .url(String.format("%s/projects/%s/pages/%s", apiBaseUrl, customGptProject, pageId))
                .delete()
                .addHeader("accept", "application/json")
                .build();
        try (Response delPageResponse = customGptClient.newCall(delPageRequest).execute()) {
            return delPageResponse.isSuccessful();
        }
    }

    private static Response addPage(OkHttpClient customGptClient, String customGptProject, String title, String output, String apiBaseUrl) throws IOException {
        // Build multipart body
        final MediaType mediaType = MediaType.parse("text/html");
        final RequestBody addDocBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file_data_retension", "false")
                .addFormDataPart("is_ocr_enabled", "false")
                .addFormDataPart("is_anonymized", "false")
                .addFormDataPart("file", title,
                        RequestBody.create(output.getBytes(StandardCharsets.UTF_8), mediaType))
                .build();
        final Request request = new Request.Builder()
                .url(String.format("%s/projects/%s/sources", apiBaseUrl, customGptProject))
                .post(addDocBody)
                .addHeader("accept", "application/json")
                .addHeader("content-type", "multipart/form-data")
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
                .addHeader("accept", "application/json")
                .addHeader("content-type", "application/json")
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

    private static Response getJahiaPageContent(OkHttpClient jahiaClient, String url, Config config) throws IOException, InterruptedException {
        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .get()
                .addHeader("content-type", "text/html;charset=UTF-8");

        if (StringUtils.isNotEmpty(config.getJahiaUsername()) && StringUtils.isNotEmpty(config.getJahiaPassword())) {
            String credential = Credentials.basic(config.getJahiaUsername(), config.getJahiaPassword(), StandardCharsets.UTF_8);
            requestBuilder.addHeader("Authorization", credential);
        }

        final Request request = requestBuilder.build();
        boolean success = false;
        int attempts = 0;
        Response response = null;
        while(!success && attempts < CustomGptConstants.MAX_RETRIES){
            response = jahiaClient.newCall(request).execute();
            success = response.isSuccessful();
            attempts++;
            Thread.sleep(500L);
        }
        return response;
    }

    private static String getApiBaseUrl(Indexer customGptIndexer) {
        String baseUrl = customGptIndexer.getCustomGptConfig().getCustomGptApiBaseUrl();
        if (StringUtils.isEmpty(baseUrl)) {
            baseUrl = CustomGptConstants.DEFAULT_CUSTOM_GPT_API_BASE_URL;
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
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
