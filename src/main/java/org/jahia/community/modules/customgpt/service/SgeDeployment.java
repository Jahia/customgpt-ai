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
 */
public final class SgeDeployment {

    private static final SgeDeployment NOT_CONFIGURED = new SgeDeployment(null, null, false);

    private final String projectId;
    private final String shareableKey;
    private final boolean available;

    private SgeDeployment(String projectId, String shareableKey, boolean available) {
        this.projectId = projectId;
        this.shareableKey = shareableKey;
        this.available = available;
    }

    public static SgeDeployment of(String projectId, String shareableKey, boolean available) {
        if (projectId == null || projectId.isEmpty() || shareableKey == null || shareableKey.isEmpty()) {
            return NOT_CONFIGURED;
        }
        return new SgeDeployment(projectId, shareableKey, available);
    }

    /** No project id, no key, or no way to resolve them: the caller should render nothing. */
    public static SgeDeployment notConfigured() {
        return NOT_CONFIGURED;
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

    @Override
    public String toString() {
        // The key is public, but it is still not worth putting in every log line.
        return "SgeDeployment{projectId=" + projectId + ", configured=" + (shareableKey != null)
                + ", available=" + available + "}";
    }
}
