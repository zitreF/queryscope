package me.fertiz.queryscope.agent.calltree;

import java.util.List;

public record CallTree(List<CallNode> topLevelCalls, List<Integer> outsideQueryIndexes, boolean truncated) {

    public static final CallTree EMPTY = new CallTree(List.of(), List.of(), false);

    public CallTree {
        topLevelCalls = List.copyOf(topLevelCalls);
        outsideQueryIndexes = List.copyOf(outsideQueryIndexes);
    }

    public int methodCount() {
        return count(topLevelCalls);
    }

    private static int count(List<CallNode> nodes) {
        return nodes.stream().mapToInt(node -> 1 + count(node.children())).sum();
    }
}
