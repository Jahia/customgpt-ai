package org.jahia.community.modules.customgpt.graphql.extensions.models;

import java.util.Calendar;
import java.util.GregorianCalendar;
import org.jahia.community.modules.customgpt.graphql.extensions.models.GqlSiteListModel.IndexedSite;
import org.jahia.community.modules.customgpt.graphql.extensions.models.GqlSiteListModel.IndexedSite.IndexationStatus;
import org.jahia.community.modules.customgpt.service.models.Site;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link GqlSiteListModel.IndexedSite#getIndexationStatus()} (U7): a pure timestamp-comparison
 * state machine derived from {@link Site}'s scheduled/start/end {@link Calendar} fields.
 *
 * <p>Also pins that {@link IndexationStatus} has exactly 3 values ({@code SCHEDULED}, {@code STARTED},
 * {@code COMPLETED}) — there is deliberately no {@code FAILED} value, which is the documented gap
 * cross-referenced by D4 (a silently-failed indexing run cannot be distinguished from "still running").
 */
public class GqlSiteListModelTest {

    private static Calendar at(int minutesFromEpoch) {
        final Calendar c = new GregorianCalendar();
        c.setTimeInMillis(minutesFromEpoch * 60_000L);
        return c;
    }

    private static IndexationStatus statusOf(Site site) {
        return new IndexedSite(site).getIndexationStatus();
    }

    @Test
    public void getIndexationStatus_neverScheduled_returnsNull() {
        final Site site = new Site("acme", "/sites/acme");
        // all timestamps null

        assertThat(statusOf(site)).isNull();
    }

    @Test
    public void getIndexationStatus_scheduledButNotYetStarted_returnsScheduled() {
        final Site site = new Site("acme", "/sites/acme");
        site.setIndexationScheduled(at(10));
        // no indexationStart

        assertThat(statusOf(site)).isEqualTo(IndexationStatus.SCHEDULED);
    }

    @Test
    public void getIndexationStatus_scheduledBeforeStart_inProgress_returnsStarted() {
        final Site site = new Site("acme", "/sites/acme");
        site.setIndexationScheduled(at(10));
        site.setIndexationStart(at(20));
        // indexationEnd null => indexationInProgress() true (start after scheduled, no end yet)

        assertThat(statusOf(site)).isEqualTo(IndexationStatus.STARTED);
    }

    @Test
    public void getIndexationStatus_scheduledBeforeStart_notInProgress_returnsCompleted() {
        final Site site = new Site("acme", "/sites/acme");
        site.setIndexationScheduled(at(10));
        site.setIndexationStart(at(20));
        site.setIndexationEnd(at(30));
        // end after start => indexationInProgress() false

        assertThat(statusOf(site)).isEqualTo(IndexationStatus.COMPLETED);
    }

    @Test
    public void getIndexationStatus_scheduledAfterStart_reScheduleWhileOldStartLingers_returnsScheduled() {
        // Simulates a re-schedule: a previous run's start timestamp still lingers on the node, but a
        // *new* scheduling timestamp is now after it.
        final Site site = new Site("acme", "/sites/acme");
        site.setIndexationStart(at(10));
        site.setIndexationEnd(at(20));
        site.setIndexationScheduled(at(30));

        assertThat(statusOf(site)).isEqualTo(IndexationStatus.SCHEDULED);
    }

    @Test
    public void indexationStatus_hasExactlyThreeValues_noFailedState() {
        // Pins the documented gap (cross-referenced by D4/U7): there is no FAILED status, so a caller
        // polling listSites cannot distinguish "still running" from "silently failed and gave up".
        assertThat(IndexationStatus.values())
                .extracting(Enum::name)
                .containsExactly("SCHEDULED", "STARTED", "COMPLETED");
    }
}
