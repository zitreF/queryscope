package me.fertiz.queryscope.agent.trace;

import me.fertiz.queryscope.agent.data.CallNode;
import me.fertiz.queryscope.agent.data.CallTree;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallTracerTest {

    private static final String SERVICE = "com.acme.OrderService.summaries";
    private static final String MAPPER = "com.acme.OrderMapper.toDto";

    @BeforeAll
    static void enableCallTree() {
        CallTracer.enable();
    }

    @Test
    void nestsMethodsAndCountsTheirCallsAndQueries() {
        CallTreeBuilder tree = CallTracer.unitStarted(System.nanoTime());
        int service = CallTracer.methodEntered(SERVICE);
        for (int index = 0; index < 3; index++) {
            int mapper = CallTracer.methodEntered(MAPPER);
            CallTracer.queryAdded(tree, index);
            CallTracer.methodExited(mapper);
        }
        CallTracer.methodExited(service);
        CallTree result = CallTracer.unitFinished(tree, System.nanoTime());

        CallNode summaries = single(result.calls());
        CallNode toDto = single(summaries.children());
        assertEquals(SERVICE, summaries.method());
        assertEquals(1, summaries.calls());
        assertEquals(List.of(), summaries.queryIndexes());
        assertEquals(MAPPER, toDto.method());
        assertEquals(3, toDto.calls());
        assertEquals(List.of(0, 1, 2), toDto.queryIndexes());
        assertTrue(summaries.totalNanos() >= toDto.totalNanos());
        assertEquals(summaries.totalNanos() - toDto.totalNanos(), summaries.selfNanos());
        assertFalse(result.truncated());
    }

    @Test
    void keepsQueriesOutsideApplicationMethodsOnTheUnit() {
        CallTreeBuilder tree = CallTracer.unitStarted(System.nanoTime());
        CallTracer.queryAdded(tree, 0);
        CallTree result = CallTracer.unitFinished(tree, System.nanoTime());

        assertEquals(List.of(), result.calls());
        assertEquals(List.of(0), result.outsideQueryIndexes());
    }

    @Test
    void marksMethodsThatWereAlreadyRunningWhenTheUnitStarted() {
        int job = CallTracer.methodEntered("com.acme.ImportJob.run");
        CallTreeBuilder tree = CallTracer.unitStarted(System.nanoTime());
        int save = CallTracer.methodEntered("com.acme.Repository.save");
        CallTracer.methodExited(save);
        CallTree result = CallTracer.unitFinished(tree, System.nanoTime());
        CallTracer.methodExited(job);

        CallNode run = single(result.calls());
        assertTrue(run.startedBeforeUnit());
        assertEquals(1, run.calls());
        assertFalse(single(run.children()).startedBeforeUnit());
    }

    @Test
    void foldsDirectRecursionIntoOneNode() {
        CallTreeBuilder tree = CallTracer.unitStarted(System.nanoTime());
        int outer = CallTracer.methodEntered("com.acme.Tree.walk");
        int middle = CallTracer.methodEntered("com.acme.Tree.walk");
        int inner = CallTracer.methodEntered("com.acme.Tree.walk");
        CallTracer.methodExited(inner);
        CallTracer.methodExited(middle);
        CallTracer.methodExited(outer);
        CallTree result = CallTracer.unitFinished(tree, System.nanoTime());

        CallNode walk = single(result.calls());
        assertEquals(3, walk.calls());
        assertEquals(List.of(), walk.children());
    }

    @Test
    void unwindsFramesWhoseExitWasMissed() {
        CallTreeBuilder tree = CallTracer.unitStarted(System.nanoTime());
        int outer = CallTracer.methodEntered("com.acme.Job.outer");
        CallTracer.methodEntered("com.acme.Job.inner");
        CallTracer.methodExited(outer);
        int next = CallTracer.methodEntered("com.acme.Job.next");
        CallTracer.methodExited(next);
        CallTree result = CallTracer.unitFinished(tree, System.nanoTime());

        assertEquals(List.of("com.acme.Job.outer", "com.acme.Job.next"),
                result.calls().stream().map(CallNode::method).toList());
    }

    @Test
    void stopsAddingMethodsAtTheNodeLimit() {
        CallTreeBuilder tree = CallTracer.unitStarted(System.nanoTime());
        int root = CallTracer.methodEntered("com.acme.Report.render");
        for (int index = 0; index < CallTreeBuilder.MAX_NODES + 10; index++) {
            int cell = CallTracer.methodEntered("com.acme.Report.cell" + index);
            CallTracer.methodExited(cell);
        }
        CallTracer.methodExited(root);
        CallTree result = CallTracer.unitFinished(tree, System.nanoTime());

        assertTrue(result.truncated());
        assertEquals(CallTreeBuilder.MAX_NODES - 1, single(result.calls()).children().size());
    }

    private static CallNode single(List<CallNode> nodes) {
        assertEquals(1, nodes.size(), nodes.toString());
        return nodes.get(0);
    }
}
