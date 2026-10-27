package me.fertiz.queryscope.agent.trace;

import me.fertiz.queryscope.agent.data.CallTree;

import java.util.Arrays;
import java.util.Optional;

public final class CallTracer {

    private static final ThreadLocal<CallStack> STACKS = ThreadLocal.withInitial(CallStack::new);
    private static volatile boolean enabled;

    private CallTracer() {
    }

    public static void enable() {
        enabled = true;
    }

    public static int methodEntered(String method) {
        return STACKS.get().push(method);
    }

    public static void methodExited(int depth) {
        if (depth > 0) {
            STACKS.get().popTo(depth);
        }
    }

    static Optional<String> outermostMethod() {
        return enabled ? STACKS.get().outermost() : Optional.empty();
    }

    static CallTreeBuilder unitStarted(long startedNanos) {
        if (!enabled) {
            return null;
        }
        CallTreeBuilder tree = new CallTreeBuilder();
        STACKS.get().attach(tree, startedNanos);
        return tree;
    }

    static void queryAdded(CallTreeBuilder tree, int index) {
        if (tree != null) {
            STACKS.get().queryAdded(tree, index);
        }
    }

    static CallTree unitFinished(CallTreeBuilder tree, long endNanos) {
        if (tree == null) {
            return CallTree.EMPTY;
        }
        STACKS.get().detach(tree, endNanos);
        return tree.build();
    }

    private static final class CallStack {

        private String[] methods = new String[32];
        private long[] starts = new long[32];
        private boolean[] recursive = new boolean[32];
        private CallTreeBuilder.Node[] nodes = new CallTreeBuilder.Node[32];
        private int depth;
        private CallTreeBuilder tree;

        int push(String method) {
            if (depth == methods.length) {
                grow();
            }
            boolean repeats = depth > 0 && method.equals(methods[depth - 1]);
            methods[depth] = method;
            recursive[depth] = repeats;
            if (tree != null) {
                CallTreeBuilder.Node node = repeats ? nodes[depth - 1] : tree.child(parentNode(), method);
                if (node != null) {
                    node.called();
                }
                nodes[depth] = node;
                starts[depth] = System.nanoTime();
            }
            return ++depth;
        }

        void popTo(int frameDepth) {
            if (frameDepth > depth) {
                return;
            }
            long now = tree == null ? 0 : System.nanoTime();
            while (depth >= frameDepth) {
                depth--;
                CallTreeBuilder.Node node = nodes[depth];
                if (node != null && !recursive[depth]) {
                    node.spent(now - starts[depth]);
                }
                nodes[depth] = null;
                methods[depth] = null;
            }
        }

        void attach(CallTreeBuilder tree, long startedNanos) {
            this.tree = tree;
            for (int index = 0; index < depth; index++) {
                CallTreeBuilder.Node parent = index == 0 ? tree.root() : nodes[index - 1];
                CallTreeBuilder.Node node = recursive[index] ? parent : tree.child(parent, methods[index]);
                if (node != null) {
                    node.calledBeforeUnit();
                }
                nodes[index] = node;
                starts[index] = startedNanos;
            }
        }

        void queryAdded(CallTreeBuilder tree, int index) {
            if (this.tree != tree) {
                return;
            }
            for (int frame = depth - 1; frame >= 0; frame--) {
                if (nodes[frame] != null) {
                    nodes[frame].queryRan(index);
                    return;
                }
            }
            tree.root().queryRan(index);
        }

        void detach(CallTreeBuilder tree, long endNanos) {
            if (this.tree != tree) {
                return;
            }
            for (int index = 0; index < depth; index++) {
                if (nodes[index] != null && !recursive[index]) {
                    nodes[index].spent(endNanos - starts[index]);
                }
                nodes[index] = null;
            }
            this.tree = null;
        }

        Optional<String> outermost() {
            return depth == 0 ? Optional.empty() : Optional.of(methods[0]);
        }

        private CallTreeBuilder.Node parentNode() {
            return depth == 0 ? tree.root() : nodes[depth - 1];
        }

        private void grow() {
            int length = methods.length * 2;
            methods = Arrays.copyOf(methods, length);
            starts = Arrays.copyOf(starts, length);
            recursive = Arrays.copyOf(recursive, length);
            nodes = Arrays.copyOf(nodes, length);
        }
    }
}
