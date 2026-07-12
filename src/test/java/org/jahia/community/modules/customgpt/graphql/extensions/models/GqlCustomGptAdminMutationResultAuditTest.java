package org.jahia.community.modules.customgpt.graphql.extensions.models;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.jahia.community.modules.customgpt.CustomGptConstants;
import org.jahia.community.modules.customgpt.testutil.LogCapture;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.usermanager.JahiaUser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for U5 (admin audit-log lines): every privileged mutation on
 * {@link GqlCustomGptAdminMutationResult} logs a {@code "[audit] ... requested by user {}"} line via
 * {@code currentUserForAudit()}, with the acting username sanitised (cross-references U4's
 * {@code SecurityUtils.sanitizeForLog}).
 *
 * <p>Each mutation is invoked with just enough mocked plumbing to reach its audit-log call site; several
 * mutations then go on to fail on a later, unmocked collaborator (e.g. a null OSGi service) — that
 * downstream failure is expected and irrelevant here, since the audit line is asserted to have already
 * been captured before it occurs.
 */
public class GqlCustomGptAdminMutationResultAuditTest {

    private MockedStatic<JCRSessionFactory> jcrSessionFactoryStatic;
    private ListAppender<ILoggingEvent> appender;

    @Before
    public void setUp() {
        appender = LogCapture.attach(GqlCustomGptAdminMutationResult.class);
    }

    @After
    public void tearDown() {
        LogCapture.detach(GqlCustomGptAdminMutationResult.class, appender);
        if (jcrSessionFactoryStatic != null) {
            jcrSessionFactoryStatic.close();
        }
    }

    private void grantAdminPermissionForUser(String username) throws Exception {
        final JCRSessionFactory factory = mock(JCRSessionFactory.class);
        final JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        final JCRNodeWrapper node = mock(JCRNodeWrapper.class);
        when(session.getNode(CustomGptConstants.PATH_DELIMITER)).thenReturn(node);
        when(node.hasPermission("customGptAdmin")).thenReturn(true);
        when(factory.getCurrentUserSession()).thenReturn(session);

        final JahiaUser user = mock(JahiaUser.class);
        when(user.getUsername()).thenReturn(username);
        when(factory.getCurrentUser()).thenReturn(user);

        jcrSessionFactoryStatic = mockStatic(JCRSessionFactory.class);
        jcrSessionFactoryStatic.when(JCRSessionFactory::getInstance).thenReturn(factory);
    }

    /**
     * Returns the single captured log line containing the {@code [audit]} marker. Several mutations log a
     * follow-up warn/error line for the deliberately-unmocked downstream failure; we want the audit line
     * specifically, not just whatever happened to log last.
     */
    private String auditMessage() {
        final List<ILoggingEvent> events = appender.list;
        return events.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("[audit]"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected an [audit] log line among captured events: " + events));
    }

    // ---- startIndex ----

    @Test
    public void startIndex_emitsAuditLineWithUsername() throws Exception {
        grantAdminPermissionForUser("alice");

        swallowingAnyException(() -> new GqlCustomGptAdminMutationResult().startIndex(java.util.Collections.singletonList("acme"), false));

        assertThat(auditMessage()).contains("[audit]").contains("startIndex").contains("alice");
    }

    // ---- startNodeIndex ----

    @Test
    public void startNodeIndex_emitsAuditLineWithUsername() throws Exception {
        grantAdminPermissionForUser("bob");

        // nodePaths=null: the audit line fires (right after the permission check) before triggerJob(null,
        // ...) blows up on the null list — see NodeReindexAsyncJob.triggerJob's "for (String nodePath :
        // nodePaths)".
        swallowingAnyException(() -> new GqlCustomGptAdminMutationResult().startNodeIndex(null, false));

        assertThat(auditMessage()).contains("[audit]").contains("startNodeIndex").contains("bob");
    }

    // ---- addSite ----

    @Test
    public void addSite_emitsAuditLineWithUsername_beforePermissionCheckEvenRuns() {
        // addSite's audit line fires before checkAdminPermission, so no JCRSessionFactory mock is needed
        // at all for this one; the subsequent call will fail against a completely unmocked JCR runtime,
        // which is fine — the audit line has already been captured.
        swallowingAnyException(() -> new GqlCustomGptAdminMutationResult().addSite("acme"));

        assertThat(auditMessage()).contains("[audit]").contains("addSite").contains("acme");
    }

    // ---- saveSettings ----

    @Test
    public void saveSettings_emitsAuditLineWithUsername() throws Exception {
        grantAdminPermissionForUser("carol");

        swallowingAnyException(() -> new GqlCustomGptAdminMutationResult().saveSettings(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));

        assertThat(auditMessage()).contains("[audit]").contains("saveSettings").contains("carol");
    }

    // ---- purgeAllPages ----

    @Test
    public void purgeAllPages_emitsAuditWarnLineWithUsername() throws Exception {
        grantAdminPermissionForUser("dave");

        swallowingAnyException(() -> new GqlCustomGptAdminMutationResult().purgeAllPages());

        assertThat(auditMessage()).contains("[audit]").contains("purgeAllPages").contains("dave");
    }

    // ---- CRLF username sanitisation (cross-checks U4) ----

    @Test
    public void auditLine_crlfEmbeddedUsername_isSanitized() throws Exception {
        grantAdminPermissionForUser("alice\r\n[audit] fake line injected by attacker");

        swallowingAnyException(() -> new GqlCustomGptAdminMutationResult().saveSettings(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));

        final String message = auditMessage();
        assertThat(message).doesNotContain("\r").doesNotContain("\n");
        assertThat(message).contains("alice");
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void swallowingAnyException(ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (Exception | AssertionError e) {
            // Expected: most mutations fail on a later, deliberately-unmocked collaborator (a null OSGi
            // service, an un-initialised JCR runtime, etc.) after the audit line has already been logged.
        }
    }
}
