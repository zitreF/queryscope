package me.fertiz.queryscope.agent.profile;

import me.fertiz.queryscope.agent.trace.AppPackages;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Profile {

    public static final String ROOT = "all";
    public static final String OUTSIDE_YOUR_CODE = "(outside your code)";
    public static final String TRUNCATED = "(truncated stack)";

    private static final String AGENT_PACKAGE = "me.fertiz.queryscope.agent.";

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
        Map<Integer, String> names = new HashMap<>();
        Map<Integer, Boolean> application = new HashMap<>();
        Map<Integer, Boolean> agent = new HashMap<>();
        Map<String, Counts> counts = new LinkedHashMap<>();
        Map<Long, Integer> perThread = new LinkedHashMap<>();
        Node root = new Node(ROOT, false);
        int samples = 0;
        int agentSamples = 0;
        for (Sample sample : store.between(filter.fromMillis(), filter.toMillis())) {
            if ((!filter.includeNative() && !sample.onCpu()) || !filter.covers(sample)) {
                continue;
            }
            perThread.merge(sample.threadId(), 1, Integer::sum);
            if (filter.threadId() != Filter.ALL_THREADS && sample.threadId() != filter.threadId()) {
                continue;
            }
            int[] frames = sample.frames();
            String[] methods = new String[frames.length];
            boolean[] app = new boolean[frames.length];
            int deepestApp = -1;
            boolean inAgent = false;
            for (int index = 0; index < frames.length; index++) {
                int id = frames[index];
                methods[index] = names.computeIfAbsent(id, store::method);
                app[index] = application.computeIfAbsent(id, key -> packages.covers(typeOf(names.get(key))));
                inAgent |= agent.computeIfAbsent(id, key -> names.get(key).startsWith(AGENT_PACKAGE));
                if (app[index]) {
                    deepestApp = index;
                }
            }
            if (inAgent) {
                agentSamples++;
                continue;
            }
            samples++;
            addPath(root, path(methods, app, sample.truncated(), filter.appOnly()));
            count(counts, methods, app, deepestApp);
        }
        List<ThreadSamples> threads = perThread.entrySet().stream()
                .map(entry -> new ThreadSamples(entry.getKey(), store.threadName(entry.getKey()), entry.getValue()))
                .sorted(Comparator.comparingInt(ThreadSamples::samples).reversed())
                .toList();
        return new Profile(samples, agentSamples, root, hottest(counts), threads);
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

    private static List<PathFrame> path(String[] methods, boolean[] app, boolean truncated, boolean appOnly) {
        List<PathFrame> path = new ArrayList<>(methods.length + 1);
        if (!appOnly) {
            if (truncated) {
                path.add(new PathFrame(TRUNCATED, false));
            }
            for (int index = 0; index < methods.length; index++) {
                path.add(new PathFrame(methods[index], app[index]));
            }
            return path;
        }
        for (int index = 0; index < methods.length; index++) {
            if (app[index]) {
                path.add(new PathFrame(methods[index], true));
            }
        }
        if (path.isEmpty()) {
            path.add(new PathFrame(OUTSIDE_YOUR_CODE, false));
        }
        int leaf = methods.length - 1;
        if (!app[leaf]) {
            path.add(new PathFrame(methods[leaf], false));
        }
        return path;
    }

    private static void addPath(Node root, List<PathFrame> path) {
        Node node = root;
        node.total++;
        for (PathFrame frame : path) {
            node = node.child(frame);
            node.total++;
        }
        node.self++;
    }

    private static void count(Map<String, Counts> counts, String[] methods, boolean[] app, int deepestApp) {
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < methods.length; index++) {
            boolean application = app[index];
            if (seen.add(methods[index])) {
                counts.computeIfAbsent(methods[index], method -> new Counts(application)).total++;
            }
        }
        counts.get(methods[methods.length - 1]).self++;
        if (deepestApp >= 0) {
            counts.get(methods[deepestApp]).own++;
        }
    }

    private static List<HotMethod> hottest(Map<String, Counts> counts) {
        List<HotMethod> all = counts.entrySet().stream()
                .map(entry -> new HotMethod(entry.getKey(), entry.getValue().app, entry.getValue().self,
                        entry.getValue().total, entry.getValue().own))
                .toList();
        Map<String, HotMethod> hot = new LinkedHashMap<>();
        all.stream()
                .filter(method -> method.self() > 0)
                .sorted(Comparator.comparingInt(HotMethod::self).reversed())
                .limit(MAX_HOT)
                .forEach(method -> hot.put(method.method(), method));
        all.stream()
                .filter(method -> method.own() > 0)
                .sorted(Comparator.comparingInt(HotMethod::own).reversed())
                .limit(MAX_HOT)
                .forEach(method -> hot.putIfAbsent(method.method(), method));
        return List.copyOf(hot.values());
    }

    private static String typeOf(String method) {
        int end = method.lastIndexOf('.');
        return end < 0 ? method : method.substring(0, end);
    }

    public record Filter(long fromMillis, long toMillis, long threadId, boolean appOnly, boolean includeNative,
                         List<Window> windows) {

        public static final long ALL_THREADS = 0;

        public Filter {
            windows = List.copyOf(windows);
        }

        public static Filter everything() {
            return new Filter(0, Long.MAX_VALUE, ALL_THREADS, false, false, List.of());
        }

        boolean covers(Sample sample) {
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

    public record HotMethod(String method, boolean app, int self, int total, int own) {
    }

    public record ThreadSamples(long id, String name, int samples) {
    }

    private record PathFrame(String method, boolean app) {
    }

    private static final class Counts {

        private final boolean app;
        private int self;
        private int total;
        private int own;

        private Counts(boolean app) {
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

        private Node child(PathFrame frame) {
            return children.computeIfAbsent(frame.method(), method -> new Node(method, frame.app()));
        }
    }
}
