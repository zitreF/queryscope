package me.fertiz.queryscope.agent.profiler;

import me.fertiz.queryscope.agent.appcode.AppPackages;
import me.fertiz.queryscope.agent.appcode.CallSites;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public final class Profile {

    public static final String OUTSIDE_YOUR_CODE = "(outside your code)";
    public static final String TRUNCATED = "(truncated stack)";

    private static final String ROOT = "all";
    private static final int MAX_HOT = 100;

    private final int samples;
    private final int agentSamples;
    private final Node root;
    private final List<HotMethod> hot;
    private final List<ThreadSamples> threads;

    private Profile(int samples, int agentSamples, Node root, List<HotMethod> hot, List<ThreadSamples> threads) {
        this.samples = samples;
        this.agentSamples = agentSamples;
        this.root = root;
        this.hot = hot;
        this.threads = threads;
    }

    public static Profile build(SampleStore store, AppPackages packages, Filter filter) {
        Map<Integer, Frame> frames = new HashMap<>();
        Map<String, MethodCounts> counts = new LinkedHashMap<>();
        Map<Long, Integer> perThread = new LinkedHashMap<>();
        Node root = new Node(ROOT, false);
        int samples = 0;
        int agentSamples = 0;
        for (Sample sample : store.samplesBetween(filter.fromMillis(), filter.toMillis())) {
            if ((!filter.includeNative() && !sample.onCpu()) || !filter.isInWindows(sample)) {
                continue;
            }
            boolean selectedThread = filter.threadId() == Filter.ALL_THREADS || sample.threadId() == filter.threadId();
            Frame[] stack = new Frame[sample.frames().length];
            boolean inAgent = false;
            for (int index = 0; index < stack.length; index++) {
                stack[index] = frames.computeIfAbsent(sample.frames()[index],
                        id -> Frame.of(store.methodName(id), packages));
                inAgent |= stack[index].agent();
            }
            if (inAgent) {
                if (selectedThread) {
                    agentSamples++;
                }
                continue;
            }
            perThread.merge(sample.threadId(), 1, Integer::sum);
            if (!selectedThread) {
                continue;
            }
            samples++;
            addPath(root, filter.appOnly() ? yourCodePath(stack) : fullPath(stack, sample.truncated()));
            countHotMethods(counts, stack);
        }
        return new Profile(samples, agentSamples, root, hottest(counts), threads(store, perThread));
    }

    public int samples() {
        return samples;
    }

    public int agentSamples() {
        return agentSamples;
    }

    public Node root() {
        return root;
    }

    public List<HotMethod> hot() {
        return hot;
    }

    public List<ThreadSamples> threads() {
        return threads;
    }

    private static List<Frame> fullPath(Frame[] stack, boolean truncated) {
        List<Frame> path = new ArrayList<>(stack.length + 1);
        if (truncated) {
            path.add(Frame.TRUNCATED_STACK);
        }
        path.addAll(Arrays.asList(stack));
        return path;
    }

    private static List<Frame> yourCodePath(Frame[] stack) {
        List<Frame> path = new ArrayList<>();
        for (Frame frame : stack) {
            if (frame.app()) {
                path.add(frame);
            }
        }
        if (path.isEmpty()) {
            path.add(Frame.OUTSIDE);
        }
        Frame leaf = stack[stack.length - 1];
        if (!leaf.app()) {
            path.add(leaf);
        }
        return path;
    }

    private static void addPath(Node root, List<Frame> path) {
        Node node = root;
        node.total++;
        for (Frame frame : path) {
            node = node.child(frame);
            node.total++;
        }
        node.self++;
    }

    private static void countHotMethods(Map<String, MethodCounts> counts, Frame[] stack) {
        Set<String> seen = new HashSet<>();
        Frame deepestApp = null;
        for (Frame frame : stack) {
            if (seen.add(frame.method())) {
                counts.computeIfAbsent(frame.method(), method -> new MethodCounts(frame.app())).total++;
            }
            if (frame.app()) {
                deepestApp = frame;
            }
        }
        counts.get(stack[stack.length - 1].method()).self++;
        if (deepestApp != null) {
            counts.get(deepestApp.method()).selfWithLibraries++;
        }
    }

    private static List<HotMethod> hottest(Map<String, MethodCounts> counts) {
        List<HotMethod> all = counts.entrySet().stream()
                .map(entry -> new HotMethod(entry.getKey(), entry.getValue().app, entry.getValue().self,
                        entry.getValue().total, entry.getValue().selfWithLibraries))
                .toList();
        return Stream.concat(
                        all.stream()
                                .filter(method -> method.self() > 0)
                                .sorted(Comparator.comparingInt(HotMethod::self).reversed())
                                .limit(MAX_HOT),
                        all.stream()
                                .filter(method -> method.selfWithLibraries() > 0)
                                .sorted(Comparator.comparingInt(HotMethod::selfWithLibraries).reversed())
                                .limit(MAX_HOT))
                .distinct()
                .toList();
    }

    private static List<ThreadSamples> threads(SampleStore store, Map<Long, Integer> perThread) {
        return perThread.entrySet().stream()
                .map(entry -> new ThreadSamples(entry.getKey(), store.threadName(entry.getKey()), entry.getValue()))
                .sorted(Comparator.comparingInt(ThreadSamples::samples).reversed())
                .toList();
    }

    private static String classOf(String method) {
        int end = method.lastIndexOf('.');
        return end < 0 ? method : method.substring(0, end);
    }

    public record Filter(long fromMillis, long toMillis, long threadId, boolean appOnly, boolean includeNative,
                         List<Window> windows) {

        public static final long ALL_THREADS = 0;

        public Filter {
            windows = List.copyOf(windows);
        }

        public static Filter allSamples() {
            return new Filter(0, Long.MAX_VALUE, ALL_THREADS, false, false, List.of());
        }

        boolean isInWindows(Sample sample) {
            if (windows.isEmpty()) {
                return true;
            }
            for (Window window : windows) {
                if (window.threadId() == sample.threadId() && sample.epochMillis() >= window.fromMillis()
                        && sample.epochMillis() <= window.toMillis()) {
                    return true;
                }
            }
            return false;
        }
    }

    public record Window(long threadId, long fromMillis, long toMillis) {
    }

    public record HotMethod(String method, boolean app, int self, int total, int selfWithLibraries) {
    }

    public record ThreadSamples(long id, String name, int samples) {
    }

    private record Frame(String method, boolean app, boolean agent) {

        private static final Frame TRUNCATED_STACK = new Frame(TRUNCATED, false, false);
        private static final Frame OUTSIDE = new Frame(OUTSIDE_YOUR_CODE, false, false);

        private static Frame of(String method, AppPackages packages) {
            return new Frame(method, packages.includesClass(classOf(method)),
                    method.startsWith(CallSites.AGENT_PACKAGE_PREFIX));
        }
    }

    private static final class MethodCounts {

        private final boolean app;
        private int self;
        private int total;
        private int selfWithLibraries;

        private MethodCounts(boolean app) {
            this.app = app;
        }
    }

    public static final class Node {

        private final String method;
        private final boolean app;
        private final Map<String, Node> children = new LinkedHashMap<>();
        private int total;
        private int self;

        private Node(String method, boolean app) {
            this.method = method;
            this.app = app;
        }

        public String method() {
            return method;
        }

        public boolean app() {
            return app;
        }

        public int total() {
            return total;
        }

        public int self() {
            return self;
        }

        public List<Node> children() {
            return children.values().stream().sorted(Comparator.comparingInt(Node::total).reversed()).toList();
        }

        private Node child(Frame frame) {
            return children.computeIfAbsent(frame.method(), method -> new Node(method, frame.app()));
        }
    }
}
