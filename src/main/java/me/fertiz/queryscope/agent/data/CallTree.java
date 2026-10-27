package me.fertiz.queryscope.agent.data;

import java.util.List;

public record CallTree(List<CallNode> calls, List<Integer> outsideQueryIndexes, boolean truncated) {

    public static final CallTree EMPTY = new CallTree(List.of(), List.of(), false);

    public CallTree {
        calls = List.copyOf(calls);
        outsideQueryIndexes = List.copyOf(outsideQueryIndexes);
    }

    public int methods() {
        return count(calls);
    }

    private static int count(List<CallNode> nodes) {
        return nodes.stream().mapToInt(node -> 1 + count(node.children())).sum();
    }
}
