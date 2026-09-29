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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Regression tests for failure containment in {@link IndexerJCRListener#onEvent}.
 *
 * <p>Context (JAHIACOM-1675): {@code JCRObservationManager.consume} runs listeners inline inside
 * {@code JCRSessionWrapper.save()} and does not isolate them, so anything thrown from {@code onEvent} aborts the
 * caller's save. In the reported incident a listener left over from an uninstalled bundle threw
 * {@code NoClassDefFoundError} while loading its own {@code IndexOperations$CustomGptOperationType}, which killed
 * an unrelated {@code PublicationJob}. Indexing is a side effect and must never be able to do that.
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
        when(service.acceptablePathToIndex(anyString())).thenThrow(failure);
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
     * A listener that cannot load its own classes has outlived its bundle and can never recover, so it must go
     * inert instead of failing every subsequent publication on the node.
     */
    @Test
    public void onEvent_becomesInert_afterItsOwnClassCannotBeLoaded() throws Exception {
        listener.onEvent(singleEventFailingInService(
                new NoClassDefFoundError("IndexOperations$CustomGptOperationType")));
        clearInvocations(service);

        final EventIterator laterEvents = mock(EventIterator.class);
        listener.onEvent(laterEvents);

        // Short-circuited before the iterator is even touched.
        verifyNoInteractions(laterEvents);
        verifyNoInteractions(service);
    }

    /** An ordinary runtime failure is logged and contained too, but does not disable the listener. */
    @Test
    public void onEvent_doesNotPropagate_onRuntimeException() throws Exception {
        final EventIterator events = singleEventFailingInService(new IllegalStateException("transient glitch"));

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
