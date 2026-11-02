package me.fertiz.queryscope.agent.calltree;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record CallTree(List<CallNode> topLevelCalls, List<Integer> outsideQueryIndexes, boolean truncated) {

    public static final CallTree EMPTY = new CallTree(List.of(), List.of(), false);
    public static final String ASYNC_TASKS = "(async tasks)";

    public CallTree {
        topLevelCalls = List.copyOf(topLevelCalls);
        outsideQueryIndexes = List.copyOf(outsideQueryIndexes);
    }

    public int methodCount() {
        return count(topLevelCalls);
    }

    public CallTree withAsyncCalls(List<AsyncCalls> tasks) {
        List<CallNode> methods = List.of();
        List<Integer> queries = new ArrayList<>();
        long totalNanos = 0;
        boolean tasksTruncated = false;
        for (AsyncCalls task : tasks) {
            methods = merge(methods, task.tree().topLevelCalls().stream().map(node -> shifted(node, task)).toList());
            queries.addAll(shifted(task.tree().outsideQueryIndexes(), task));
            totalNanos += task.durationNanos();
            tasksTruncated |= task.tree().truncated();
        }
        List<CallNode> calls = new ArrayList<>(topLevelCalls);
        calls.add(new CallNode(ASYNC_TASKS, tasks.size(), totalNanos, 0, false, queries, methods));
        return new CallTree(calls, outsideQueryIndexes, truncated || tasksTruncated);
    }

    private static int count(List<CallNode> nodes) {
        return nodes.stream().mapToInt(node -> 1 + count(node.children())).sum();
    }

    private static CallNode shifted(CallNode node, AsyncCalls task) {
        return new CallNode(node.method(), node.calls(), node.totalNanos(), node.selfNanos(), node.startedBeforeUnit(),
                shifted(node.queryIndexes(), task), node.children().stream().map(child -> shifted(child, task)).toList());
    }

    private static List<Integer> shifted(List<Integer> queryIndexes, AsyncCalls task) {
        return queryIndexes.stream().map(task::shift).filter(index -> index >= 0).toList();
    }

    private static List<CallNode> merge(List<CallNode> nodes, List<CallNode> more) {
        Map<String, CallNode> byMethod = new LinkedHashMap<>();
        nodes.forEach(node -> byMethod.merge(node.method(), node, CallTree::combine));
        more.forEach(node -> byMethod.merge(node.method(), node, CallTree::combine));
        return List.copyOf(byMethod.values());
    }

    private static CallNode combine(CallNode first, CallNode second) {
        List<Integer> queries = new ArrayList<>(first.queryIndexes());
        queries.addAll(second.queryIndexes());
        return new CallNode(first.method(), first.calls() + second.calls(), first.totalNanos() + second.totalNanos(),
                first.selfNanos() + second.selfNanos(), first.startedBeforeUnit() || second.startedBeforeUnit(),
                queries, merge(first.children(), second.children()));
    }
}
