package org.jahia.community.modules.customgpt.service;

import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import org.jahia.community.modules.customgpt.indexer.listener.IndexOperations;
import org.jahia.community.modules.customgpt.indexer.listener.IndexOperations.CustomGptIndexOperation;
import org.jahia.community.modules.customgpt.indexer.listener.IndexOperations.CustomGptOperationType;
import org.junit.Before;
import org.junit.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Regression tests for coalescing repeated index operations for the same node.
 *
 * <p>One publication performs several JCR saves, each delivering its own event batch, and the listener builds a
 * fresh {@code IndexOperations} per batch — so its internal de-duplication cannot see across batches and the same
 * node was dispatched up to 10 times for one publication. Each repeat costs a DELETE plus a POST against an API
 * that degrades under sustained volume.
 *
 * <p>The executor is a mock that never runs the submitted task, which keeps the first dispatch permanently
 * "pending" — exactly the window in which a repeat from the same publication arrives.
 */
public class ServiceCoalescingTest {

    private static final String NODE = "/sites/acme/home/page";

    private Service service;
    private ExecutorService executor;

    @Before
    public void setUp() throws Exception {
        service = Service.class.getDeclaredConstructor().newInstance();
        executor = mock(ExecutorService.class);
        set("executor", executor);
    }

    private void set(String field, Object value) throws Exception {
        final Field f = Service.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(service, value);
    }

    private static IndexOperations indexOf(String path) {
        final IndexOperations ops = new IndexOperations();
        ops.addOperation(new CustomGptIndexOperation(CustomGptOperationType.NODE_INDEX, path));
        return ops;
    }

    private static IndexOperations removeOf(String path) {
        final IndexOperations ops = new IndexOperations();
        ops.addOperation(new CustomGptIndexOperation(CustomGptOperationType.NODE_REMOVE, path));
        return ops;
    }

    /** The defect: N saves in one publication dispatched N times for the same node. */
    @Test
    public void repeatDispatchForTheSameNodeIsCoalescedWhileTheFirstIsStillPending() {
        service.produceAsynchronousOperations(indexOf(NODE));
        service.produceAsynchronousOperations(indexOf(NODE));
        service.produceAsynchronousOperations(indexOf(NODE));

        verify(executor, times(1)).execute(any());
    }

    /** Different nodes must not be coalesced against each other. */
    @Test
    public void differentNodesAreDispatchedIndependently() {
        service.produceAsynchronousOperations(indexOf(NODE));
        service.produceAsynchronousOperations(indexOf("/sites/acme/home/other"));

        verify(executor, times(2)).execute(any());
    }

    /** A removal and an indexation of the same node are different work and must both run. */
    @Test
    public void removalIsNotCoalescedAgainstIndexation() {
        service.produceAsynchronousOperations(indexOf(NODE));
        service.produceAsynchronousOperations(removeOf(NODE));

        verify(executor, times(2)).execute(any());
    }

    /**
     * Once the work completes the node must be dispatchable again — a key left behind would silently stop that
     * node ever being indexed for the life of the component.
     */
    @Test
    public void theNodeIsDispatchableAgainOnceTheWorkCompletes() throws Exception {
        // This executor runs the task inline, so the first dispatch completes before the second arrives.
        final ExecutorService inline = mock(ExecutorService.class);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(inline).execute(any());
        set("executor", inline);

        service.produceAsynchronousOperations(indexOf(NODE));
        service.produceAsynchronousOperations(indexOf(NODE));

        verify(inline, times(2)).execute(any());
    }
}
