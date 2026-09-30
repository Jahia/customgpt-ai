package org.jahia.community.modules.customgpt.graphql.extensions.models;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import org.jahia.community.modules.customgpt.service.models.Site;

@GraphQLName("siteList")
@GraphQLDescription("List of indexed sites")
public class GqlSiteListModel {

    private final Collection<Site> indexedSites;

    public GqlSiteListModel(Collection<Site> indexedSites) {
        this.indexedSites = new ArrayList<>(indexedSites);
    }

    @GraphQLField
    @GraphQLDescription("Total number of indexed sites")
    public int getTotalCount() {
        return indexedSites.size();
    }

    @GraphQLField
    @GraphQLDescription("Indexed sites")
    public List<IndexedSite> getSites() {
        return indexedSites
                .stream()
                .map(IndexedSite::new)
                .collect(Collectors.toList());
    }

    @GraphQLName("indexedSite")
    @GraphQLDescription("Representation of indexed site")
    public static class IndexedSite {

        @GraphQLName("indexationStatus")
        @GraphQLDescription("Status of indexation")
        public enum IndexationStatus {
            @GraphQLDescription("Indexation has been scheduled")
            SCHEDULED,
            @GraphQLDescription("Indexation has started")
            STARTED,
            @GraphQLDescription("Indexation has completed")
            COMPLETED,
            @GraphQLDescription("Indexation ended with at least one failed operation")
            FAILED
        }

        private final String siteKey;
        private final boolean indexationInProgress;
        private final boolean indexationFailed;
        private final Instant indexationStart;
        private final Instant indexationEnd;
        private final Instant indexationScheduled;

        public IndexedSite(Site site) {
            this.siteKey = site.getSiteKey();
            this.indexationInProgress = site.indexationInProgress();
            this.indexationFailed = site.indexationFailedLastRun();
            this.indexationStart = toInstant(site.getIndexationStart());
            this.indexationEnd = toInstant(site.getIndexationEnd());
            this.indexationScheduled = toInstant(site.getIndexationScheduled());
        }

        private Instant toInstant(Calendar date) {
            return date == null ? null : date.toInstant();
        }

        @GraphQLField
        @GraphQLDescription("Site key")
        public String getSiteKey() {
            return siteKey;
        }

        @GraphQLField
        @GraphQLDescription("Status of indexation")
        public IndexationStatus getIndexationStatus() {
            if (this.indexationScheduled == null) {
                return null;
            } else if (indexationStart == null || indexationScheduled.isAfter(indexationStart)) {
                return IndexationStatus.SCHEDULED;
            } else if (indexationInProgress) {
                return IndexationStatus.STARTED;
            } else if (indexationFailed) {
                // Never report COMPLETED for a run that did not complete: the end timestamp is written even on
                // failure so the site does not appear stuck, so the failure marker is the only truthful signal.
                return IndexationStatus.FAILED;
            } else {
                return IndexationStatus.COMPLETED;
            }
        }

        @GraphQLField
        @GraphQLDescription("Start time of most recent indexation as ISO string")
        public String getIndexationStart() {
            if (indexationStart == null) {
                return null;
            }

            return indexationStart.toString();
        }

        @GraphQLField
        @GraphQLDescription("End time of most recent indexation as ISO string")
        public String getIndexationEnd() {
            if (indexationEnd == null) {
                return null;
            }

            return indexationEnd.toString();
        }

        @GraphQLField
        @GraphQLDescription("Scheduled time of most recent indexation as ISO string")
        public String getIndexationScheduled() {
            if (indexationScheduled == null) {
                return null;
            }

            return indexationScheduled.toString();
        }
    }
}
