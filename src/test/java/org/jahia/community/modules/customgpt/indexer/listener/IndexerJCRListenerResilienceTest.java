package org.jahia.community.modules.customgpt.indexer.listener;

import javax.jcr.RepositoryException;
import javax.jcr.observation.EventIterator;
import org.jahia.community.modules.customgpt.service.Service;
import org.jahia.community.modules.customgpt.settings.Config;
import org.jahia.services.content.JCRObservationManager.EventWrapper;
import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression tests for failure containment in {@link IndexerJCRListener#onEvent}.
 *
 * <p>Context (JAHIACOM-1675): {@code JCRObservationManager.consume} runs listeners inline inside
 * {@code JCRSessionWrapper.save()}. It wraps the call in {@code catch (Exception)}, so ordinary exceptions are
 * already contained by the platform - but an {@link Error} propagates and aborts the caller's save. In the
 * reported incident a listener left over from an uninstalled bundle threw {@code NoClassDefFoundError} while
 * loading its own {@code IndexOperations$CustomGptOperationType}, which killed an unrelated
 * {@code PublicationJob}. The {@code LinkageError} arm is therefore the load-bearing one; the
 * {@code RuntimeException} arm only buys a better log line. Containment is all these tests assert: the listener
 * deliberately does not try to decide, from a message string, that its own classloader is dead.
 */
public class IndexerJCRListenerResilienceTest {

    private Service service;
    private IndexerJCRListener listener;

    @Before
    public void setUp() {
        service = mock(Service.class);
        listener = new IndexerJCRListener(true, service, mock(Config.class));
    }

    /** An iterator over one event on a watched path, ready for the collaborator under test to be sabotaged. */
    private EventIterator singleEvent(EventWrapper event) {
        final EventIterator events = mock(EventIterator.class);
        when(events.hasNext()).thenReturn(true, false);
        when(events.nextEvent()).thenReturn(event);
        return events;
    }

    /** Feeds the listener one event whose acceptability check blows up in the requested (unchecked) way. */
    private EventIterator singleEventFailingInService(Throwable failure) throws RepositoryException {
        final EventWrapper event = mock(EventWrapper.class);
        when(event.getPath()).thenReturn("/sites/acme/home");
        // doThrow/when, not when/thenThrow: the latter would *invoke* an already-stubbed method, so re-stubbing
        // inside a loop would throw the previous failure outside the code under test.
        doThrow(failure).when(service).acceptablePathToIndex(anyString());
        return singleEvent(event);
    }

    /** The exact failure from the incident: a class this bundle owns can no longer be loaded. */
    @Test
    public void onEvent_doesNotPropagate_whenItsOwnClassCannotBeLoaded() throws Exception {
        final EventIterator events = singleEventFailingInService(
                new NoClassDefFoundError("org/jahia/community/modules/customgpt/indexer/listener/"
                        + "IndexOperations$CustomGptOperationType"));

        assertThatCode(() -> listener.onEvent(events)).doesNotThrowAnyException();
    }





    /**
     * An ordinary runtime failure must not stop the listener processing later events. The second pass has to get
     * all the way to dispatching an operation: merely re-entering the loop would also happen if the listener had
     * quietly disabled itself, so stubbing the failure once and then succeeding is what makes this meaningful.
     */
    @Test
    public void onEvent_keepsProcessing_afterRuntimeException() throws Exception {
        final EventWrapper failing = mock(EventWrapper.class);
        when(failing.getPath()).thenReturn("/sites/acme/home");
        when(service.acceptablePathToIndex(anyString()))
                .thenThrow(new IllegalStateException("transient glitch"))
                .thenReturn(false);

        listener.onEvent(singleEvent(failing));
        clearInvocations(service);

        final EventWrapper later = mock(EventWrapper.class);
        when(later.getPath()).thenReturn("/sites/acme/other");
        listener.onEvent(singleEvent(later));

        // Reached the collaborator again on the second pass rather than short-circuiting.
        verify(service).acceptablePathToIndex("/sites/acme/other");
    }

    /**
     * The {@code LinkageError} arm must cover the whole family, not just {@code NoClassDefFoundError}. A bundle
     * whose wiring was replaced can equally produce {@code NoSuchMethodError} or {@code IncompatibleClassChangeError},
     * and any of them escaping aborts the caller's save - Jahia contains {@code Exception}, not {@code Error}.
     */
    @Test
    public void onEvent_doesNotPropagate_onOtherLinkageErrors() throws Exception {
        for (LinkageError err : new LinkageError[]{
                new NoSuchMethodError("org.jahia.services.content.JCRNodeWrapper.getPath()"),
                new IncompatibleClassChangeError("org/apache/jackrabbit/core/ItemManager")}) {
            assertThatCode(() -> listener.onEvent(singleEventFailingInService(err)))
                    .as("%s must be contained", err.getClass().getSimpleName())
                    .doesNotThrowAnyException();
        }
    }

    /**
     * A {@code LinkageError} naming another bundle's class must be contained too. The listener deliberately does
     * not try to decide, from a message string, whose classloader is at fault - so narrowing the arm to "only
     * swallow errors that name us" would let a stale listener abort publications again.
     */
    @Test
    public void onEvent_doesNotPropagate_whenTheFailedClassBelongsToAnotherBundle() throws Exception {
        final EventIterator events = singleEventFailingInService(
                new NoClassDefFoundError("org/apache/jackrabbit/core/session/SessionItemOperation"));

        assertThatCode(() -> listener.onEvent(events)).doesNotThrowAnyException();
    }

    /** A repository failure is contained, as it already was before this change. */
    @Test
    public void onEvent_doesNotPropagate_onRepositoryException() throws Exception {
        final EventWrapper event = mock(EventWrapper.class);
        when(event.getPath()).thenThrow(new RepositoryException("repository unavailable"));
        final EventIterator events = singleEvent(event);

        assertThatCode(() -> listener.onEvent(events)).doesNotThrowAnyException();
    }
}
