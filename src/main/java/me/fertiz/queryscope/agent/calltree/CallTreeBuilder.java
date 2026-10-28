package me.fertiz.queryscope.agent.calltree;


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CallTreeBuilder {

    static final int MAX_NODES = 1000;
    static final int MAX_DEPTH = 100;

    private final Node root = new Node(null, 0);
    private int nodes;
    private boolean truncated;
    private long lastExitNanos;

    Node root() {
        return root;
    }

    long lastExitNanos() {
        return lastExitNanos;
    }

    void methodExited(long nanos) {
        lastExitNanos = nanos;
    }

    Node child(Node parent, String method) {
        if (parent == null) {
            return null;
        }
        Node child = parent.children.get(method);
        if (child != null) {
            return child;
        }
        if (nodes >= MAX_NODES || parent.depth >= MAX_DEPTH) {
            truncated = true;
            return null;
        }
        nodes++;
        child = new Node(method, parent.depth + 1);
        parent.children.put(method, child);
        return child;
    }

    CallTree build() {
        return new CallTree(root.builtChildren(), root.queryIndexes, truncated);
    }

    static final class Node {

        private final String method;
        private final int depth;
        private final Map<String, Node> children = new LinkedHashMap<>(4);
        private final List<Integer> queryIndexes = new ArrayList<>(0);
        private long calls;
        private long totalNanos;
        private boolean startedBeforeUnit;

        private Node(String method, int depth) {
            this.method = method;
            this.depth = depth;
        }

        void called() {
            calls++;
        }

        void calledBeforeUnit() {
            calls++;
            startedBeforeUnit = true;
        }

        void spent(long nanos) {
            totalNanos += Math.max(0, nanos);
        }

        void queryRan(int index) {
            queryIndexes.add(index);
        }

        private CallNode build() {
            List<CallNode> built = builtChildren();
            long childNanos = built.stream().mapToLong(CallNode::totalNanos).sum();
            return new CallNode(method, calls, totalNanos, Math.max(0, totalNanos - childNanos), startedBeforeUnit,
                    queryIndexes, built);
        }

        private List<CallNode> builtChildren() {
            return children.values().stream().map(Node::build).toList();
        }
    }
}
