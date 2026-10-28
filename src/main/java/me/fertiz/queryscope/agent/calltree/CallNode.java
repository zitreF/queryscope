package me.fertiz.queryscope.agent.calltree;

import java.util.List;

public record CallNode(
        String method,
        long calls,
        long totalNanos,
        long selfNanos,
        boolean startedBeforeUnit,
        List<Integer> queryIndexes,
        List<CallNode> children
) {

    public CallNode {
        queryIndexes = List.copyOf(queryIndexes);
        children = List.copyOf(children);
    }
}
