package me.fertiz.queryscope.agent.profiler;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class SampleStore {

    private static final int MAX_SHARED_STACKS = 20_000;
    private static final int MAX_THREADS = 5_000;

    private final Clock clock;
    private final int capacity;
    private final long maxAgeMillis;
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private final Map<String, Integer> methodIds = new HashMap<>();
    private final List<String> methods = new ArrayList<>();
    private final Map<Long, String> threadNames = new HashMap<>();
    private final Map<FrameIds, int[]> sharedStacks = new HashMap<>();
    private volatile String source;
    private volatile long intervalMillis;

    public SampleStore(Clock clock, int capacity, Duration maxAge) {
        this.clock = clock;
        this.capacity = capacity;
        this.maxAgeMillis = maxAge.toMillis();
    }

    public void markStarted(String source, Duration interval) {
        this.intervalMillis = interval.toMillis();
        this.source = source;
    }

    public String source() {
        return source;
    }

    public long intervalMillis() {
        return intervalMillis;
    }

    public long maxAgeMillis() {
        return maxAgeMillis;
    }

    public long now() {
        return clock.millis();
    }

    public synchronized void add(long epochMillis, long threadId, String threadName, boolean onCpu, boolean truncated,
                                 List<String> rootFirstFrames) {
        if (rootFirstFrames.isEmpty()) {
            return;
        }
        if (threadName != null) {
            rememberThread(threadId, threadName);
        }
        samples.addLast(new Sample(epochMillis, threadId, onCpu, truncated, internedFrameIds(rootFirstFrames)));
        evictExpired();
    }

    public synchronized List<Sample> samplesBetween(long fromMillis, long toMillis) {
        evictExpired();
        List<Sample> selected = new ArrayList<>();
        for (Sample sample : samples) {
            if (sample.epochMillis() >= fromMillis && sample.epochMillis() <= toMillis) {
                selected.add(sample);
            }
        }
        return selected;
    }

    public synchronized String methodName(int id) {
        return methods.get(id);
    }

    public synchronized String threadName(long threadId) {
        return threadNames.getOrDefault(threadId, "thread " + threadId);
    }

    private int[] internedFrameIds(List<String> rootFirstFrames) {
        int[] frames = new int[rootFirstFrames.size()];
        for (int index = 0; index < frames.length; index++) {
            frames[index] = methodId(rootFirstFrames.get(index));
        }
        if (sharedStacks.size() >= MAX_SHARED_STACKS) {
            sharedStacks.clear();
        }
        return sharedStacks.computeIfAbsent(new FrameIds(frames), FrameIds::frames);
    }

    private void rememberThread(long threadId, String threadName) {
        if (threadNames.size() >= MAX_THREADS) {
            threadNames.clear();
        }
        threadNames.put(threadId, threadName);
    }

    private int methodId(String method) {
        Integer id = methodIds.get(method);
        if (id == null) {
            id = methods.size();
            methods.add(method);
            methodIds.put(method, id);
        }
        return id;
    }

    private void evictExpired() {
        long oldest = clock.millis() - maxAgeMillis;
        while (!samples.isEmpty() && (samples.size() > capacity || samples.peekFirst().epochMillis() < oldest)) {
            samples.removeFirst();
        }
    }

    private record FrameIds(int[] frames) {

        @Override
        public boolean equals(Object other) {
            return other instanceof FrameIds ids && Arrays.equals(frames, ids.frames);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(frames);
        }
    }
}
