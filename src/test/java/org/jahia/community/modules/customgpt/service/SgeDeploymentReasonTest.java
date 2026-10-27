package org.jahia.community.modules.customgpt.service;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reason carried by every {@link SgeDeployment}.
 *
 * <p>Four distinct faults used to collapse into one indistinguishable "not configured", which on a cluster
 * made "this node cannot reach the API" look exactly like "the feature is switched off here" -- and the
 * only way to observe either was whether a button appeared on a rendered page. The reason is what the admin
 * GraphQL field reports, so these assertions are what keeps that field able to tell the cases apart.
 */
public class SgeDeploymentReasonTest {

    private static final String ID = "68402";
    private static final String KEY = "031eb8c9280864685bb92899580e04bf";

    @Test
    public void aResolvedDeploymentSaysSo() {
        final SgeDeployment resolved = SgeDeployment.of(ID, KEY, true);
        assertThat(resolved.getReason()).isEqualTo(SgeDeployment.REASON_RESOLVED);
        assertThat(resolved.isUsable()).isTrue();
    }

    /** Out of quota is a resolution, not a failure: the id and key are good, the answer is "not now". */
    @Test
    public void quotaSpentIsDistinctFromEveryFailure() {
        final SgeDeployment spent = SgeDeployment.of(ID, KEY, false);
        assertThat(spent.getReason()).isEqualTo(SgeDeployment.REASON_QUOTA_SPENT);
        assertThat(spent.isUsable()).isFalse();
        assertThat(spent.getProjectId()).isEqualTo(ID);
    }

    @Test
    public void eachFailureKeepsItsOwnReason() {
        assertThat(SgeDeployment.notConfigured(SgeDeployment.REASON_NO_PROJECT_ID).getReason())
                .isEqualTo(SgeDeployment.REASON_NO_PROJECT_ID);
        assertThat(SgeDeployment.notConfigured(SgeDeployment.REASON_NO_HTTP_CLIENT).getReason())
                .isEqualTo(SgeDeployment.REASON_NO_HTTP_CLIENT);
        assertThat(SgeDeployment.notConfigured(SgeDeployment.REASON_BAD_API_BASE_URL).getReason())
                .isEqualTo(SgeDeployment.REASON_BAD_API_BASE_URL);
        assertThat(SgeDeployment.notConfigured(SgeDeployment.REASON_NO_DEPLOYMENT_KEY).getReason())
                .isEqualTo(SgeDeployment.REASON_NO_DEPLOYMENT_KEY);
    }

    /** The six reasons must stay distinct, or the field reports a difference that is not there. */
    @Test
    public void theReasonsAreAllDifferent() {
        assertThat(new String[]{
                SgeDeployment.REASON_RESOLVED,
                SgeDeployment.REASON_QUOTA_SPENT,
                SgeDeployment.REASON_NO_PROJECT_ID,
                SgeDeployment.REASON_NO_HTTP_CLIENT,
                SgeDeployment.REASON_BAD_API_BASE_URL,
                SgeDeployment.REASON_NO_DEPLOYMENT_KEY,
                SgeDeployment.REASON_NOT_CONFIGURED})
                .doesNotHaveDuplicates();
    }

    /** No failure may look embeddable: a reason must never arrive with a usable id and key beside it. */
    @Test
    public void noFailureCarriesSomethingEmbeddable() {
        final SgeDeployment failed = SgeDeployment.notConfigured(SgeDeployment.REASON_NO_DEPLOYMENT_KEY);
        assertThat(failed.getProjectId()).isNull();
        assertThat(failed.getShareableKey()).isNull();
        assertThat(failed.isUsable()).isFalse();
    }

    /** Half a configuration is not embeddable, and must not claim to have resolved. */
    @Test
    public void anIncompletePairDoesNotReportAsResolved() {
        assertThat(SgeDeployment.of(ID, null, true).getReason()).isEqualTo(SgeDeployment.REASON_NOT_CONFIGURED);
        assertThat(SgeDeployment.of(null, KEY, true).getReason()).isEqualTo(SgeDeployment.REASON_NOT_CONFIGURED);
        assertThat(SgeDeployment.of("", "", true).isUsable()).isFalse();
    }

    /** The reason is read by a GraphQL field and printed into logs; a null there helps nobody. */
    @Test
    public void theReasonIsNeverNull() {
        assertThat(SgeDeployment.notConfigured().getReason()).isNotNull();
        assertThat(SgeDeployment.of(ID, KEY, true).getReason()).isNotNull();
        assertThat(SgeDeployment.of(null, null, false).getReason()).isNotNull();
    }
}
