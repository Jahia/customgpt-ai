package org.jahia.community.modules.customgpt.service;

import java.util.Calendar;
import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.junit.Test;
import org.mockito.MockedStatic;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for how a site indexation's outcome is recorded.
 *
 * <p>The admin UI derives its status from properties on the site node, and previously nothing recorded that a run
 * had failed: {@code whenCompleteAsync} discarded its {@code throwable} and the end timestamp was written
 * regardless, so a run in which every operation failed was reported as COMPLETED over partial or empty data.
 */
public class ServiceIndexationOutcomeTest {

    private static final String SITE = "/sites/acme";
    private static final String END = "customGptIndexationEnd";
    private static final String FAILED = "customGptIndexationFailed";

    private JCRSessionWrapper lastSession;

    /** Runs {@code recordIndexationOutcome} against a mocked JCR and returns the site node it wrote to. */
    private JCRNodeWrapper record(Throwable throwable) throws Exception {
        return record(throwable, null);
    }

    /**
     * As above, but {@code failOn} names a property whose write blows up - standing in for the
     * ConstraintViolationException a property missing from the CND raises.
     */
    private JCRNodeWrapper record(Throwable throwable, String failOn) throws Exception {
        final Service service = Service.class.getDeclaredConstructor().newInstance();
        final JCRNodeWrapper siteNode = mock(JCRNodeWrapper.class);
        final JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        lastSession = session;
        when(session.getNode(SITE)).thenReturn(siteNode);
        if (failOn != null) {
            org.mockito.Mockito.doThrow(new javax.jcr.nodetype.ConstraintViolationException("no such property: " + failOn))
                    .when(siteNode).setProperty(eq(failOn), any(Calendar.class));
        }

        final JCRTemplate template = mock(JCRTemplate.class);
        when(template.doExecuteWithSystemSession(any())).thenAnswer(invocation -> {
            final JCRCallback<?> callback = invocation.getArgument(0);
            return callback.doInJCR(session);
        });

        try (MockedStatic<JCRTemplate> statics = mockStatic(JCRTemplate.class)) {
            statics.when(JCRTemplate::getInstance).thenReturn(template);
            service.recordIndexationOutcome(SITE, throwable);
        }
        return siteNode;
    }

    @Test
    public void recordIndexationOutcome_onSuccess_writesEndAndNoFailureMarker() throws Exception {
        final JCRNodeWrapper siteNode = record(null);

        verify(siteNode).setProperty(eq(END), any(Calendar.class));
        verify(siteNode, never()).setProperty(eq(FAILED), any(Calendar.class));
    }

    /**
     * The failure marker and the end timestamp must be committed together.
     *
     * <p>They were two separate sessions, each with its own save. customGptIndexationFailed was also missing from
     * the CND, so its write raised ConstraintViolationException AFTER the end timestamp had already been saved -
     * leaving a site that had failed every node looking exactly like one that finished cleanly. One session, one
     * save: if either property cannot be written, neither is.
     */
    @Test
    public void recordIndexationOutcome_onFailure_commitsNothingWhenTheMarkerCannotBeWritten() throws Exception {
        record(new IllegalStateException("indexation blew up"), FAILED);

        verify(lastSession, never()).save();
    }

    @Test
    public void recordIndexationOutcome_writesBothPropertiesInASingleSave() throws Exception {
        record(new IllegalStateException("indexation blew up"));

        // One save, not one per property - a half-written outcome is the bug being fixed.
        verify(lastSession).save();
    }

    /** The point of the fix: a failed run must not be indistinguishable from a successful one. */
    @Test
    public void recordIndexationOutcome_onFailure_writesTheFailureMarker() throws Exception {
        final JCRNodeWrapper siteNode = record(new IllegalStateException("indexation blew up"));

        verify(siteNode).setProperty(eq(FAILED), any(Calendar.class));
        // The end timestamp is still written, so the site does not appear stuck mid-run.
        verify(siteNode).setProperty(eq(END), any(Calendar.class));
    }
}
