package org.jahia.community.modules.customgpt.indexer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.commons.lang.StringUtils;
import org.jahia.community.modules.customgpt.settings.Config;
import org.jahia.community.modules.customgpt.util.SecurityUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders a page through Jahia's own GraphQL endpoint, authenticated with a personal API token.
 *
 * <p>This replaces fetching the public {@code .html} URL with HTTP Basic credentials. Three things drove the
 * change, and only the first was the original complaint:
 *
 * <ul>
 *   <li>Basic authentication is blocked in some environments. A personal API token travels as
 *       {@code Authorization: APIToken <value>}, a different scheme, and carries no password.</li>
 *   <li>The call goes to a LOCAL endpoint, so it never crosses the proxy, WAF or bot protection that sit in
 *       front of the public host - which is also why the public-page fetch needed a configurable user agent.</li>
 *   <li>Content the indexing account cannot read comes back as a structured {@code Permission denied} with a
 *       null node, not as a 200 carrying a login form or an authorization notice. Those pages can be skipped
 *       precisely instead of being uploaded as if they were content.</li>
 * </ul>
 *
 * <p>Rendering still happens inside a real servlet request - the GraphQL servlet's - which is what makes it work
 * at all: Jahia executes JSP views by dispatching through the container, so a render driven from outside a
 * request cannot produce them.
 */
public final class JahiaRenderClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(JahiaRenderClient.class);

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final String HEADER_AUTHORIZATION = "Authorization";
    private static final String TOKEN_SCHEME = "APIToken ";

    /**
     * {@code contextConfiguration: "page"} yields the complete document - doctype, head and body - rather than a
     * fragment. Measured against the public URL for the same page: 22,332 bytes versus 21,951, and 4,190 visible
     * characters versus 4,185.
     */
    private static final String RENDER_QUERY =
            "query($path:String!,$language:String!){"
            + "jcr(workspace: LIVE){"
            + "nodeByPath(path:$path){"
            + "renderedContent(templateType:\"html\",contextConfiguration:\"page\",language:$language){output}"
            + "}}}";

    private JahiaRenderClient() {
        // Utility class.
    }

    /** Raised when the indexing account cannot see the node: expected, and not an indexing failure. */
    public static class NotVisibleToIndexerException extends Exception {

        private static final long serialVersionUID = 1L;

        public NotVisibleToIndexerException(String message) {
            super(message);
        }
    }

    /**
     * Renders a node and returns its HTML.
     *
     * @param nodePath the LIVE path of the node to render
     * @param language the language to render in
     * @throws NotVisibleToIndexerException when the indexing account may not read the node
     * @throws IOException on a transport or protocol failure, which IS an indexing failure
     */
    public static String render(OkHttpClient jahiaClient, Config config, String nodePath, String language)
            throws IOException, NotVisibleToIndexerException {
        final String endpoint = config.getJahiaGraphqlEndpoint();
        if (StringUtils.isEmpty(endpoint)) {
            throw new IOException("No Jahia GraphQL endpoint is configured; cannot render " + nodePath);
        }
        final String token = config.getJahiaApiToken();
        if (StringUtils.isEmpty(token)) {
            throw new IOException("No Jahia API token is configured; cannot render " + nodePath);
        }

        final JSONObject variables = new JSONObject().put("path", nodePath).put("language", language);
        final JSONObject payload = new JSONObject().put("query", RENDER_QUERY).put("variables", variables);

        final Request.Builder builder = new Request.Builder()
                .url(endpoint)
                .post(RequestBody.create(payload.toString().getBytes(StandardCharsets.UTF_8), JSON))
                .header(HEADER_AUTHORIZATION, TOKEN_SCHEME + token);
        applyOptionalHeaders(builder, config);

        try (Response response = jahiaClient.newCall(builder.build()).execute()) {
            if (response.body() == null) {
                throw new IOException("Empty response from the Jahia GraphQL endpoint for " + nodePath);
            }
            final String body = response.body().string();
            if (!response.isSuccessful()) {
                // The token itself being refused is a configuration fault, not a per-node access decision, so it
                // must not be mistaken for "the indexer cannot see this page".
                throw new IOException("Jahia GraphQL returned HTTP " + response.code() + " for " + nodePath
                        + (response.code() == 401 || response.code() == 403
                                ? ". Check that the configured API token is valid and scoped to graphql." : ""));
            }
            return extractOutput(new JSONObject(body), nodePath);
        }
    }

    /**
     * Pulls the rendered HTML out of the GraphQL envelope, distinguishing "not allowed to see this" from
     * "something went wrong".
     */
    static String extractOutput(JSONObject envelope, String nodePath) throws IOException, NotVisibleToIndexerException {
        final JSONObject data = envelope.optJSONObject("data");
        final JSONObject jcr = data == null ? null : data.optJSONObject("jcr");
        final JSONObject node = jcr == null ? null : jcr.optJSONObject("nodeByPath");
        final JSONObject rendered = node == null ? null : node.optJSONObject("renderedContent");
        final String output = rendered == null ? null : rendered.optString("output", null);

        if (StringUtils.isNotEmpty(output)) {
            return output;
        }

        final String errors = describeErrors(envelope);
        if (isAccessDenied(envelope)) {
            throw new NotVisibleToIndexerException(nodePath + " is not readable by the indexing account: " + errors);
        }
        throw new IOException("Jahia GraphQL returned no rendered output for " + nodePath
                + (errors.isEmpty() ? "" : ": " + errors));
    }

    /**
     * Whether the envelope says the caller lacks access.
     *
     * <p>Matched on the classification rather than the message, which is human-facing and may be localised or
     * reworded. An access decision has to be told apart from a transport failure reliably: treated as a failure
     * it would mark every site FAILED on every run, and treated as content it would upload an authorization
     * notice into the corpus.
     */
    private static boolean isAccessDenied(JSONObject envelope) {
        final JSONArray errors = envelope.optJSONArray("errors");
        if (errors == null) {
            return false;
        }
        for (int i = 0; i < errors.length(); i++) {
            final JSONObject error = errors.optJSONObject(i);
            if (error == null) {
                continue;
            }
            final String type = error.optString("errorType", "");
            final JSONObject extensions = error.optJSONObject("extensions");
            final String classification = extensions == null ? "" : extensions.optString("classification", "");
            if (type.contains("AccessDenied") || classification.contains("AccessDenied")) {
                return true;
            }
        }
        return false;
    }

    private static String describeErrors(JSONObject envelope) {
        final JSONArray errors = envelope.optJSONArray("errors");
        if (errors == null || errors.length() == 0) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < errors.length(); i++) {
            final JSONObject error = errors.optJSONObject(i);
            if (error != null) {
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append(SecurityUtils.sanitizeForLog(error.optString("message", "")));
            }
        }
        return sb.toString();
    }

    /**
     * Applies the server cookie and user agent when configured.
     *
     * <p>Both still earn their place even though the call is local: in a cluster the cookie pins the request to
     * a chosen node, and the agent remains useful if the endpoint is ever put behind something that inspects it.
     */
    private static void applyOptionalHeaders(Request.Builder builder, Config config) {
        final String userAgent = config.getUserAgent();
        if (StringUtils.isNotEmpty(userAgent)) {
            builder.header("User-Agent", userAgent);
        }
        final String cookieName = config.getJahiaServerCookieName();
        final String cookieValue = config.getJahiaServerCookieValue();
        if (StringUtils.isNotEmpty(cookieName) && StringUtils.isNotEmpty(cookieValue)) {
            builder.header("Cookie", cookieName + "=" + cookieValue);
        }
    }

    /** Logs a skipped node once, at INFO: expected for a deliberately restricted indexing account. */
    public static void logSkipped(String nodePath, NotVisibleToIndexerException e) {
        LOGGER.info("Skipping {}: {}", SecurityUtils.sanitizeForLog(nodePath), e.getMessage());
    }
}
