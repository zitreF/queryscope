package me.fertiz.queryscope.agent.calltree;


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

    public static Optional<String> outermostMethod() {
        return enabled ? STACKS.get().outermost() : Optional.empty();
    }

    public static CallTreeBuilder unitStarted(long startedNanos) {
        if (!enabled) {
            return null;
        }
        CallTreeBuilder tree = new CallTreeBuilder();
        STACKS.get().startRecording(tree, startedNanos);
        return tree;
    }

    public static void queryAdded(CallTreeBuilder tree, int index) {
        if (tree != null) {
            STACKS.get().queryAdded(tree, index);
        }
    }

    public static long lastExitNanos(CallTreeBuilder tree) {
        return tree == null ? 0 : tree.lastExitNanos();
    }

    public static CallTree unitFinished(CallTreeBuilder tree, long endNanos) {
        if (tree == null) {
            return CallTree.EMPTY;
        }
        STACKS.get().stopRecording(tree, endNanos);
        return tree.build();
    }

    private static final class CallStack {

        private String[] methods = new String[32];
        private long[] starts = new long[32];
        private boolean[] repeatsParent = new boolean[32];
        private CallTreeBuilder.Node[] nodes = new CallTreeBuilder.Node[32];
        private int depth;
        private CallTreeBuilder tree;

        int push(String method) {
            if (depth == methods.length) {
                grow();
            }
            boolean repeats = depth > 0 && method.equals(methods[depth - 1]);
            methods[depth] = method;
            repeatsParent[depth] = repeats;
            if (tree != null) {
                CallTreeBuilder.Node node = repeats ? nodes[depth - 1] : tree.findOrAddChild(parentOf(depth), method);
                if (node != null) {
                    node.countCall();
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
                closeFrame(depth, now);
                methods[depth] = null;
            }
        }

        void startRecording(CallTreeBuilder tree, long startedNanos) {
            this.tree = tree;
            for (int index = 0; index < depth; index++) {
                CallTreeBuilder.Node parent = parentOf(index);
                CallTreeBuilder.Node node = repeatsParent[index] ? parent : tree.findOrAddChild(parent, methods[index]);
                if (node != null) {
                    node.countCallStartedBeforeUnit();
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
                    nodes[frame].addQuery(index);
                    return;
                }
            }
            tree.root().addQuery(index);
        }

        void stopRecording(CallTreeBuilder tree, long endNanos) {
            if (this.tree != tree) {
                return;
            }
            for (int index = 0; index < depth; index++) {
                closeFrame(index, endNanos);
            }
            this.tree = null;
        }

        Optional<String> outermost() {
            return depth == 0 ? Optional.empty() : Optional.of(methods[0]);
        }

        private void closeFrame(int index, long nowNanos) {
            if (nodes[index] != null && !repeatsParent[index]) {
                nodes[index].addTime(nowNanos - starts[index]);
                tree.methodExited(nowNanos);
            }
            nodes[index] = null;
        }

        private CallTreeBuilder.Node parentOf(int index) {
            return index == 0 ? tree.root() : nodes[index - 1];
        }

        private void grow() {
            int length = methods.length * 2;
            methods = Arrays.copyOf(methods, length);
            starts = Arrays.copyOf(starts, length);
            repeatsParent = Arrays.copyOf(repeatsParent, length);
            nodes = Arrays.copyOf(nodes, length);
        }
    }
}
