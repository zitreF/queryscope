package me.fertiz.queryscope.agent.methodtrace;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class MethodTraces {

    private static final Map<String, TracedMethod> TRACED = new ConcurrentHashMap<>();

    private MethodTraces() {
    }

    public static void called(String method, long startedNanos, boolean failed) {
        TracedMethod traced = TRACED.get(method);
        if (traced != null) {
            traced.record(System.nanoTime() - startedNanos, failed);
        }
    }

    static void startRecording(String method) {
        TRACED.computeIfAbsent(method, name -> new TracedMethod(name, Instant.now()));
    }

    static void stopRecording(String method) {
        TRACED.remove(method);
    }

    static List<TracedMethod> recorded(List<String> methods) {
        return methods.stream().map(TRACED::get).filter(traced -> traced != null).toList();
    }
}
