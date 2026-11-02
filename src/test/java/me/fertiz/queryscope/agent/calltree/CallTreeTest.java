package me.fertiz.queryscope.agent.calltree;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallTreeTest {

    private static final String CONTROLLER = "com.acme.DashboardController.show";
    private static final String TOTAL = "com.acme.Orders.totalOf";
    private static final String ROUND = "com.acme.Money.round";

    @Test
    void mergesTheMethodsOfAsyncTasksByNameUnderOneNode() {
        CallTree request = new CallTree(List.of(node(CONTROLLER, 1, 9_000, List.of(0, 1), List.of())), List.of(2),
                false);
        CallTree first = new CallTree(List.of(node(TOTAL, 1, 3_000, List.of(0), List.of(node(ROUND, 2, 100,
                List.of(), List.of())))), List.of(), false);
        CallTree second = new CallTree(List.of(node(TOTAL, 1, 4_000, List.of(0), List.of(node(ROUND, 1, 50,
                List.of(), List.of())))), List.of(), false);

        CallTree merged = request.withAsyncCalls(List.of(new AsyncCalls(first, 3_500, 3, 1),
                new AsyncCalls(second, 4_500, 4, 1)));

        assertEquals(List.of(2), merged.outsideQueryIndexes());
        assertEquals(CONTROLLER, merged.topLevelCalls().get(0).method());
        CallNode async = merged.topLevelCalls().get(1);
        assertEquals(CallTree.ASYNC_TASKS, async.method());
        assertEquals(2, async.calls());
        assertEquals(8_000, async.totalNanos());
        assertEquals(0, async.selfNanos());
        CallNode total = async.children().get(0);
        assertEquals(1, async.children().size());
        assertEquals(new CallNode(TOTAL, 2, 7_000, 7_000, false, List.of(3, 4),
                List.of(new CallNode(ROUND, 3, 150, 150, false, List.of(), List.of()))), total);
        assertEquals(4, merged.methodCount());
    }

    @Test
    void dropsTheQueriesOfAsyncTasksThatDidNotFit() {
        CallTree task = new CallTree(List.of(node(TOTAL, 3, 6_000, List.of(0, 1, 2), List.of())), List.of(3), true);

        CallTree merged = CallTree.EMPTY.withAsyncCalls(List.of(new AsyncCalls(task, 7_000, 10, 2)));

        CallNode async = merged.topLevelCalls().get(0);
        assertEquals(List.of(), async.queryIndexes());
        assertEquals(List.of(10, 11), async.children().get(0).queryIndexes());
        assertTrue(merged.truncated());
        assertFalse(CallTree.EMPTY.truncated());
    }

    private static CallNode node(String method, long calls, long nanos, List<Integer> queries, List<CallNode> children) {
        return new CallNode(method, calls, nanos, nanos, false, queries, children);
    }
}
