package org.jahia.community.modules.customgpt.service;

/**
 * Everything a page needs to embed CustomGPT's Search Generative Experience widget, and nothing it must not
 * have.
 *
 * <p>Deliberately excludes the API token. The token is a credential that lives in this module's
 * configuration and is used server side; the widget authenticates with {@code shareableKey}, the public
 * deployment key that CustomGPT's own embed snippet puts in page source. Keeping the two apart in a type
 * means a caller cannot reach for the wrong one: an editor typing the key into a component property by hand
 * is one slip away from pasting the token into something the browser renders.
 *
 * <p><strong>Every instance carries a {@link #getReason() reason}.</strong> Four different faults used to
 * collapse into one indistinguishable "not configured", which on a cluster made "this node cannot reach the
 * API" look exactly like "the feature is switched off" -- with nothing anywhere to separate them. The
 * reason is what the admin GraphQL field reports, so a node can be asked directly instead of inferred from
 * the absence of a button.
 */
public final class SgeDeployment {

    /** Resolved and embeddable. */
    public static final String REASON_RESOLVED = "RESOLVED";
    /** Resolved, but the team's query quota for the billing cycle is spent. */
    public static final String REASON_QUOTA_SPENT = "QUOTA_SPENT";
    /** No project id in this node's configuration. */
    public static final String REASON_NO_PROJECT_ID = "NO_PROJECT_ID";
    /** No HTTP client on this node: {@code init()} did not run or did not complete. */
    public static final String REASON_NO_HTTP_CLIENT = "NO_HTTP_CLIENT";
    /** The configured API base URL could not be validated. */
    public static final String REASON_BAD_API_BASE_URL = "BAD_API_BASE_URL";
    /** The API did not yield a deployment key: this node could not reach it, or it refused. */
    public static final String REASON_NO_DEPLOYMENT_KEY = "NO_DEPLOYMENT_KEY";
    /** Nothing has been resolved on this node yet. */
    public static final String REASON_NOT_CONFIGURED = "NOT_CONFIGURED";

    private final String projectId;
    private final String shareableKey;
    private final boolean available;
    private final String reason;

    private SgeDeployment(String projectId, String shareableKey, boolean available, String reason) {
        this.projectId = projectId;
        this.shareableKey = shareableKey;
        this.available = available;
        this.reason = reason;
    }

    public static SgeDeployment of(String projectId, String shareableKey, boolean available) {
        if (projectId == null || projectId.isEmpty() || shareableKey == null || shareableKey.isEmpty()) {
            return notConfigured();
        }
        return new SgeDeployment(projectId, shareableKey, available,
                available ? REASON_RESOLVED : REASON_QUOTA_SPENT);
    }

    /** No project id, no key, or no way to resolve them: the caller should render nothing. */
    public static SgeDeployment notConfigured() {
        return notConfigured(REASON_NOT_CONFIGURED);
    }

    /** As {@link #notConfigured()}, recording which of the several distinct faults it was. */
    public static SgeDeployment notConfigured(String reason) {
        return new SgeDeployment(null, null, false, reason);
    }

    public String getProjectId() {
        return projectId;
    }

    /** The public deployment key. Safe in page source; this is never the API token. */
    public String getShareableKey() {
        return shareableKey;
    }

    /**
     * False only when the team's query quota for the current billing cycle is known to be used up. An
     * inconclusive check reads as true: see {@code Service#getSgeDeployment()} for why.
     */
    public boolean isAvailable() {
        return available;
    }

    /** True when the widget should be offered: configured and not known to be out of quota. */
    public boolean isUsable() {
        return projectId != null && shareableKey != null && available;
    }

    /** Why this is what it is. One of the {@code REASON_*} constants; never null. */
    public String getReason() {
        return reason;
    }

    @Override
    public String toString() {
        // The key is public, but it is still not worth putting in every log line.
        return "SgeDeployment{projectId=" + projectId + ", configured=" + (shareableKey != null)
                + ", available=" + available + ", reason=" + reason + "}";
    }
}
