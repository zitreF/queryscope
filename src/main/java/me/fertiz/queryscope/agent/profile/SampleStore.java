package me.fertiz.queryscope.agent.profile;

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
    private final Map<Stack, int[]> sharedStacks = new HashMap<>();
    private volatile String source;
    private volatile long intervalMillis;

    public SampleStore(Clock clock, int capacity, Duration maxAge) {
        this.clock = clock;
        this.capacity = capacity;
        this.maxAgeMillis = maxAge.toMillis();
    }

    public void started(String source, Duration interval) {
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
        int[] frames = new int[rootFirstFrames.size()];
        for (int index = 0; index < frames.length; index++) {
            frames[index] = methodId(rootFirstFrames.get(index));
        }
        if (sharedStacks.size() >= MAX_SHARED_STACKS) {
            sharedStacks.clear();
        }
        frames = sharedStacks.computeIfAbsent(new Stack(frames), stack -> stack.frames);
        if (threadName != null) {
            if (threadNames.size() >= MAX_THREADS) {
                threadNames.clear();
            }
            threadNames.put(threadId, threadName);
        }
        samples.addLast(new Sample(epochMillis, threadId, onCpu, truncated, frames));
        evict();
    }

    public synchronized List<Sample> between(long fromMillis, long toMillis) {
        evict();
        List<Sample> selected = new ArrayList<>();
        for (Sample sample : samples) {
            if (sample.epochMillis() >= fromMillis && sample.epochMillis() <= toMillis) {
                selected.add(sample);
            }
        }
        return selected;
    }

    public synchronized String method(int id) {
        return methods.get(id);
    }

    public synchronized String threadName(long threadId) {
        return threadNames.getOrDefault(threadId, "thread " + threadId);
    }

    public synchronized void clear() {
        samples.clear();
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

    private void evict() {
        long oldest = clock.millis() - maxAgeMillis;
        while (!samples.isEmpty() && (samples.size() > capacity || samples.peekFirst().epochMillis() < oldest)) {
            samples.removeFirst();
        }
    }

    private record Stack(int[] frames) {

        @Override
        public boolean equals(Object other) {
            return other instanceof Stack stack && Arrays.equals(frames, stack.frames);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(frames);
        }
    }
}
