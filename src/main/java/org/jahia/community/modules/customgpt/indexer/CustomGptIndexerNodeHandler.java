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
import javax.jcr.AccessDeniedException;
import javax.jcr.ItemExistsException;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import javax.jcr.lock.LockException;
import javax.jcr.version.VersionException;
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
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        deleteCustomGptPage(customGptClient, customGptIndexer.getCustomGptConfig().getCustomGptProjectId(), pageId);
    }

    private static void index(OkHttpClient customGptClient, OkHttpClient jahiaClient, IndexRequest createCustomGptRequest, Indexer customGptIndexer) throws RepositoryException {
        final JCRNodeWrapper nodeToIndex = createCustomGptRequest.getNode();
        final JCRSiteNode siteNode = nodeToIndex.getResolveSite();
        final String language = createCustomGptRequest.getLanguage();
        // Define media type for file upload

        // Set user to be use later by the system sessions.
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

                // Mocked Objects
                final HttpServletRequestMock request = new HttpServletRequestMock(new HashMap<>(), serverUrl.getHost(), serverUrl.getPath());
                final HttpServletResponseMock response = new HttpServletResponseMock(new StringWriter());
                final RenderContext customRenderContext = new RenderContext(request, response, rootUser);
                customRenderContext.setSite(siteNode);

                if (session.nodeExists(nodeToIndex.getPath())) {
                    try {
                        JCRNodeWrapper nodeInOtherLocale = session.getNode(nodeToIndex.getPath());
                        final String url = hostName + Utils.encode(nodeInOtherLocale.getUrl(), customRenderContext);

                        if (nodeInOtherLocale.hasProperty(CustomGptConstants.PROP_CUSTOM_GPT_PAGE_ID)) {
                            final Property property = nodeInOtherLocale.getProperty(CustomGptConstants.PROP_CUSTOM_GPT_PAGE_ID);
                            final String pageId = property.getString();
                            LOGGER.info(String.format("Removing page with the id %s for the url %s, language %s", pageId, url, language));
                            deletePage(nodeInOtherLocale, property, customGptClient, customGptIndexer.getCustomGptConfig().getCustomGptProjectId(), pageId);
                            nodeInOtherLocale = session.getNode(nodeToIndex.getPath());
                        }
                        LOGGER.debug(String.format("Adding url %s", url));
                        try ( Response jahiaResponse = getJahiaPageContent(jahiaClient, url)) {
                            if (jahiaResponse.isSuccessful()) {
                                LOGGER.debug(String.format("Retrieve Jahia page content is successful for %s", url));
                                final String output = jahiaResponse.body().string();
                                final String title;
                                if (nodeInOtherLocale.hasProperty(Constants.JCR_TITLE)) {
                                    title = nodeInOtherLocale.getPropertyAsString(Constants.JCR_TITLE);
                                } else {
                                    title = nodeInOtherLocale.getName();
                                }
                                if (nodeInOtherLocale.isFile()) {
                                    nodeInOtherLocale.addMixin(CustomGptConstants.MIX_CUSTOM_GPT_FILE_INDEXED);
                                } else {
                                    nodeInOtherLocale.addMixin(CustomGptConstants.MIX_CUSTOM_GPT_INDEXED);
                                }
                                nodeInOtherLocale.saveSession();
                                LOGGER.debug(String.format("Adding page in customGPT for %s", url));
                                try ( Response addDocResponse = addPage(customGptClient, customGptIndexer.getCustomGptConfig().getCustomGptProjectId(), title, output)) {
                                    if (addDocResponse.isSuccessful()) {
                                        LOGGER.debug("Adding page in customGPT is successful, retrieving response body");
                                        final String jsonResponse = addDocResponse.body().string();
                                        LOGGER.debug("Converting JSON resposne to JSON object");
                                        final JSONObject document = new JSONObject(jsonResponse);
                                        LOGGER.debug("Retrieving CustomGpt page id");
                                        final String pageId = String.valueOf(document.getJSONObject("data").getJSONArray("pages").getJSONObject(0).getLong("id"));
                                        LOGGER.debug(String.format("Adding page id %s to Jahia node %s", pageId, nodeInOtherLocale.getPath()));
                                        nodeInOtherLocale.setProperty(CustomGptConstants.PROP_CUSTOM_GPT_PAGE_ID, pageId);
                                        LOGGER.debug("Saving Jahia node");
                                        nodeInOtherLocale.saveSession();
                                        LOGGER.debug("Updating page metadata in customGPT");
                                        try ( Response metaResponse = updatePageMedata(customGptClient, customGptIndexer.getCustomGptConfig().getCustomGptProjectId(), pageId, title, url)) {
                                            if (metaResponse.isSuccessful()) {
                                                LOGGER.debug("Updating page metadata in customGPT is successful");
                                            } else {
                                                throw new IOException("Unexpected code " + metaResponse);
                                            }
                                        }
                                    } else {
                                        throw new IOException(String.format("Impossible to add the page for the URL %s, following response received, %s", url, addDocResponse));
                                    }
                                }
                            } else {
                                LOGGER.warn(String.format("Impossible to retrieve content from %s", url));
                            }
                        }
                    } catch (Exception ex) {
                        LOGGER.error("Issue:", ex);
                    }
                }
                return null;
            }
        });
    }

    private static void deletePage(JCRNodeWrapper nodeInOtherLocale, Property property, OkHttpClient customGptClient, String customGptProject, String pageId) throws IOException, LockException, VersionException, ItemExistsException, AccessDeniedException, RepositoryException {
        if (deleteCustomGptPage(customGptClient, customGptProject, pageId)) {
            property.remove();
            nodeInOtherLocale.saveSession();
        }
    }

    private static boolean deleteCustomGptPage(OkHttpClient customGptClient, String customGptProject, String pageId) throws IOException {
        LOGGER.info(String.format("Removing page with the id %s", pageId));
        final Request delPageRequest = new Request.Builder()
                .url(String.format("https://app.customgpt.ai/api/v1/projects/%s/pages/%s", customGptProject, pageId))
                .delete()
                .addHeader("accept", "application/json")
                .build();
        try ( Response delPageResponse = customGptClient.newCall(delPageRequest).execute()) {
            return delPageResponse.isSuccessful();
        }
    }

    private static Response addPage(OkHttpClient customGptClient, String customGptProject, String title, String output) throws IOException {
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
                .url(String.format("https://app.customgpt.ai/api/v1/projects/%s/sources", customGptProject))
                .post(addDocBody)
                .addHeader("accept", "application/json")
                .addHeader("content-type", "multipart/form-data")
                .build();
        return customGptClient.newCall(request).execute();
    }

    private static Response updatePageMedata(OkHttpClient customGptClient, String customGptProject, String pageId, String title, String url) throws IOException {
        final RequestBody metadataBody = new FormBody.Builder()
                .add("title", title)
                .add("url", url)
                .build();

        final Request request = new Request.Builder()
                .url(String.format("https://app.customgpt.ai/api/v1/projects/%s/pages/%s/metadata", customGptProject, pageId))
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

    private static Response getJahiaPageContent(OkHttpClient customGptClient, String url) throws IOException {

        final Request request = new Request.Builder()
                .url(url)
                .get()
                .addHeader("content-type", "text/html;charset=UTF-8")
                .build();
        return customGptClient.newCall(request).execute();
    }
}
