package me.fertiz.queryscope.agent.methodtrace;

import me.fertiz.queryscope.agent.trace.UnitTracer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

public final class TracedMethod {

    static final int RECENT_CALLS = 50;
    static final int SLOWEST_CALLS = 10;

    private final String method;
    private final Instant since;
    private final LongAdder calls = new LongAdder();
    private final LongAdder failures = new LongAdder();
    private final LongAdder totalNanos = new LongAdder();
    private final LatencyHistogram latencies = new LatencyHistogram();
    private final CallSlots recent = new CallSlots(RECENT_CALLS);
    private final CallSlots slowest = new CallSlots(SLOWEST_CALLS);
    private long maxNanos;
    private int nextRecent;
    private int fastestOfSlowest;

    TracedMethod(String method, Instant since) {
        this.method = method;
        this.since = since;
    }

    void record(long durationNanos, boolean failed) {
        calls.increment();
        totalNanos.add(durationNanos);
        if (failed) {
            failures.increment();
        }
        latencies.record(durationNanos);
        long endedAtMillis = System.currentTimeMillis();
        String thread = Thread.currentThread().getName();
        long unitId = UnitTracer.currentUnitId();
        String unit = UnitTracer.currentUnitLabel();
        synchronized (this) {
            maxNanos = Math.max(maxNanos, durationNanos);
            recent.put(nextRecent, endedAtMillis, durationNanos, failed, thread, unitId, unit);
            nextRecent = (nextRecent + 1) % RECENT_CALLS;
            if (slowest.filled < SLOWEST_CALLS) {
                slowest.put(slowest.filled, endedAtMillis, durationNanos, failed, thread, unitId, unit);
                fastestOfSlowest = slowest.fastest();
            } else if (durationNanos > slowest.durations[fastestOfSlowest]) {
                slowest.put(fastestOfSlowest, endedAtMillis, durationNanos, failed, thread, unitId, unit);
                fastestOfSlowest = slowest.fastest();
            }
        }
    }

    public synchronized Snapshot snapshot() {
        long callCount = calls.sum();
        List<TracedCall> recentCalls = recent.calls();
        recentCalls.sort(Comparator.comparingLong(TracedCall::endedAtMillis).reversed());
        List<TracedCall> slowestCalls = slowest.calls();
        slowestCalls.sort(Comparator.comparingLong(TracedCall::durationNanos).reversed());
        boolean everyCallIsRecent = recent.filled == callCount;
        return new Snapshot(method, since, callCount, failures.sum(), totalNanos.sum(), maxNanos,
                percentile(0.5, everyCallIsRecent), percentile(0.95, everyCallIsRecent),
                percentile(0.99, everyCallIsRecent), List.copyOf(recentCalls), List.copyOf(slowestCalls));
    }

    private long percentile(double fraction, boolean everyCallIsRecent) {
        if (!everyCallIsRecent) {
            return Math.min(latencies.percentile(fraction), maxNanos);
        }
        if (recent.filled == 0) {
            return 0;
        }
        long[] durations = Arrays.copyOf(recent.durations, recent.filled);
        Arrays.sort(durations);
        int rank = (int) Math.ceil(fraction * durations.length);
        return durations[Math.max(0, rank - 1)];
    }

    public record Snapshot(String method, Instant since, long calls, long failures, long totalNanos, long maxNanos,
                           long p50Nanos, long p95Nanos, long p99Nanos, List<TracedCall> recent,
                           List<TracedCall> slowest) {

        public long averageNanos() {
            return calls == 0 ? 0 : totalNanos / calls;
        }
    }

    private static final class CallSlots {

        private final long[] endedAtMillis;
        private final long[] durations;
        private final boolean[] failed;
        private final String[] threads;
        private final long[] unitIds;
        private final String[] units;
        private int filled;

        private CallSlots(int size) {
            endedAtMillis = new long[size];
            durations = new long[size];
            failed = new boolean[size];
            threads = new String[size];
            unitIds = new long[size];
            units = new String[size];
        }

        private void put(int slot, long endedAt, long duration, boolean failure, String thread, long unitId,
                         String unit) {
            endedAtMillis[slot] = endedAt;
            durations[slot] = duration;
            failed[slot] = failure;
            threads[slot] = thread;
            unitIds[slot] = unitId;
            units[slot] = unit;
            filled = Math.max(filled, slot + 1);
        }

        private int fastest() {
            int fastest = 0;
            for (int slot = 1; slot < filled; slot++) {
                if (durations[slot] < durations[fastest]) {
                    fastest = slot;
                }
            }
            return fastest;
        }

        private List<TracedCall> calls() {
            List<TracedCall> calls = new ArrayList<>(filled);
            for (int slot = 0; slot < filled; slot++) {
                calls.add(new TracedCall(endedAtMillis[slot], durations[slot], failed[slot], threads[slot],
                        unitIds[slot], units[slot]));
            }
            return calls;
        }
    }
}
