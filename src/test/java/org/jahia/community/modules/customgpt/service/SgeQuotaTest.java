package org.jahia.community.modules.customgpt.service;

import org.json.JSONObject;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the Search Generative Experience quota decision and its value type.
 *
 * <p>The decision is a pure function of the {@code GET /limits/usage} payload, so no network, OSGi or JCR is
 * involved. What is being pinned down is the fail-open policy: availability goes false only when the quota
 * is <em>positively known</em> to be spent, and every uncertain case leaves the feature on.
 */
public class SgeQuotaTest {

    private static JSONObject limits(int current, int max) {
        return new JSONObject().put("current_queries", current).put("max_queries", max);
    }

    // ---- the quota is known ----

    @Test
    public void quotaLeftWhenUsageIsBelowTheCeiling() {
        assertThat(Service.hasQueryQuotaLeft(limits(10, 1000))).isTrue();
    }

    @Test
    public void quotaLeftOnTheLastQuery() {
        assertThat(Service.hasQueryQuotaLeft(limits(999, 1000))).isTrue();
    }

    @Test
    public void noQuotaLeftWhenUsageReachesTheCeiling() {
        assertThat(Service.hasQueryQuotaLeft(limits(1000, 1000))).isFalse();
    }

    /** Usage can overshoot the ceiling between billing events; that is still spent. */
    @Test
    public void noQuotaLeftWhenUsageHasOvershotTheCeiling() {
        assertThat(Service.hasQueryQuotaLeft(limits(1200, 1000))).isFalse();
    }

    // ---- fail open: everything uncertain stays available ----

    /*
     * Hiding a working feature on a transient error is the worse of the two failures. The visitor loses a
     * route that would have worked and nothing explains why; letting a doomed query through costs one
     * CustomGPT error message, which is visible and actionable. So only a definite "spent" closes it.
     */

    @Test
    public void availableWhenThePayloadIsMissingEntirely() {
        assertThat(Service.hasQueryQuotaLeft(null)).isTrue();
    }

    @Test
    public void availableWhenThePayloadIsEmpty() {
        assertThat(Service.hasQueryQuotaLeft(new JSONObject())).isTrue();
    }

    @Test
    public void availableWhenTheCeilingIsAbsent() {
        assertThat(Service.hasQueryQuotaLeft(new JSONObject().put("current_queries", 5000))).isTrue();
    }

    @Test
    public void availableWhenCurrentUsageIsAbsent() {
        assertThat(Service.hasQueryQuotaLeft(new JSONObject().put("max_queries", 1000))).isTrue();
    }

    /** A non-positive ceiling is how an unmetered plan reads; it is not a quota of zero. */
    @Test
    public void availableWhenTheCeilingIsZeroOrNegative() {
        assertThat(Service.hasQueryQuotaLeft(limits(10, 0))).isTrue();
        assertThat(Service.hasQueryQuotaLeft(limits(10, -1))).isTrue();
    }

    @Test
    public void availableWhenTheFieldsAreNotNumbers() {
        final JSONObject garbage = new JSONObject()
                .put("current_queries", "lots")
                .put("max_queries", "some");
        assertThat(Service.hasQueryQuotaLeft(garbage)).isTrue();
    }

    // ---- the value type ----

    @Test
    public void aFullyResolvedDeploymentIsUsable() {
        final SgeDeployment deployment = SgeDeployment.of("68402", "031eb8c9280864685bb92899580e04bf", true);
        assertThat(deployment.isUsable()).isTrue();
        assertThat(deployment.getProjectId()).isEqualTo("68402");
        assertThat(deployment.getShareableKey()).isEqualTo("031eb8c9280864685bb92899580e04bf");
    }

    /** Configured but out of quota: the caller must not offer the widget. */
    @Test
    public void anExhaustedDeploymentIsNotUsable() {
        assertThat(SgeDeployment.of("68402", "031eb8c9280864685bb92899580e04bf", false).isUsable()).isFalse();
    }

    @Test
    public void aDeploymentMissingEitherValueIsNotConfigured() {
        assertThat(SgeDeployment.of(null, "031eb8c9280864685bb92899580e04bf", true).isUsable()).isFalse();
        assertThat(SgeDeployment.of("68402", null, true).isUsable()).isFalse();
        assertThat(SgeDeployment.of("", "031eb8c9280864685bb92899580e04bf", true).isUsable()).isFalse();
        assertThat(SgeDeployment.of("68402", "", true).isUsable()).isFalse();
    }

    @Test
    public void notConfiguredExposesNothing() {
        final SgeDeployment deployment = SgeDeployment.notConfigured();
        assertThat(deployment.isUsable()).isFalse();
        assertThat(deployment.getProjectId()).isNull();
        assertThat(deployment.getShareableKey()).isNull();
    }

    /** The token must never travel in this object; nothing in it should ever read like one. */
    @Test
    public void toStringDoesNotCarryTheKey() {
        final String rendered = SgeDeployment.of("68402", "031eb8c9280864685bb92899580e04bf", true).toString();
        assertThat(rendered).doesNotContain("031eb8c9280864685bb92899580e04bf");
        assertThat(rendered).contains("68402");
    }
}
