package org.jahia.community.modules.customgpt.service.models;

import java.util.Calendar;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Site {

    private static final Logger LOGGER = LoggerFactory.getLogger(Site.class);

    private final String siteKey;
    private final String path;
    private Calendar indexationStart;
    private Calendar indexationEnd;
    private Calendar indexationScheduled;

    public Site(String siteKey, String path) {
        this.siteKey = siteKey;
        this.path = path;
    }

    public String getSiteKey() {
        return siteKey;
    }

    public String getPath() {
        return path;
    }

    public Calendar getIndexationStart() {
        return indexationStart;
    }

    public void setIndexationStart(Calendar indexationStart) {
        this.indexationStart = indexationStart;
    }

    public void setIndexationStart(Calendar indexationStart, Callable<Object> onStartCallback) {
        this.indexationStart = indexationStart;
        this.indexationEnd = null;
        try {
            onStartCallback.call();
        } catch (Exception e) {
            LOGGER.error("Callback failed: {}", e.getMessage(), e);
        }
    }

    public Calendar getIndexationEnd() {
        return indexationEnd;
    }

    public void setIndexationEnd(Calendar indexationEnd) {
        this.indexationEnd = indexationEnd;
    }

    public void setIndexationEnd(Calendar indexationEnd, Callable<Object> onEndCallback) {
        this.indexationEnd = indexationEnd;
        try {
            onEndCallback.call();
        } catch (Exception e) {
            LOGGER.error("Failed to call callback: {}", e.getMessage(), e);
        }
    }

    public boolean indexationInProgress() {
        final boolean isScheduledIndexationStarted
                = indexationStart != null && indexationScheduled != null && indexationStart.toInstant().isAfter(indexationScheduled.toInstant());
        return isScheduledIndexationStarted && (indexationEnd == null || indexationEnd.before(indexationStart));
    }

    @Override
    public String toString() {
        return "Site{"
                + "siteKey='" + siteKey + '\''
                + ", path='" + path + '\''
                + ", indexationStart=" + (indexationStart != null ? indexationStart.toInstant() : null)
                + ", indexationEnd=" + (indexationEnd != null ? indexationEnd.toInstant() : null)
                + ", indexationScheduled=" + (indexationScheduled != null ? indexationScheduled.toInstant() : null)
                + ", indexationInProgress=" + indexationInProgress()
                + '}';
    }

    public void setIndexationScheduled(Calendar indexationScheduled) {
        this.indexationScheduled = indexationScheduled;
    }

    public Calendar getIndexationScheduled() {
        return indexationScheduled;
    }
}
