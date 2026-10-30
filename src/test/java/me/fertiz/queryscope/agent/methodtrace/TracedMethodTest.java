package me.fertiz.queryscope.agent.methodtrace;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TracedMethodTest {

    private final TracedMethod traced = new TracedMethod("com.acme.OrderService.findAll", Instant.EPOCH);

    @Test
    void countsCallsFailuresAndTime() {
        traced.record(2_000_000, false);
        traced.record(4_000_000, true);

        TracedMethod.Snapshot snapshot = traced.snapshot();

        assertEquals(2, snapshot.calls());
        assertEquals(1, snapshot.failures());
        assertEquals(6_000_000, snapshot.totalNanos());
        assertEquals(3_000_000, snapshot.averageNanos());
        assertEquals(4_000_000, snapshot.maxNanos());
        assertEquals(Thread.currentThread().getName(), snapshot.recent().get(0).thread());
        assertEquals(List.of(4_000_000L, 2_000_000L),
                snapshot.slowest().stream().map(TracedCall::durationNanos).toList());
    }

    @Test
    void reportsExactPercentilesWhileEveryCallIsKept() {
        traced.record(486_000_000, false);
        traced.record(491_000_000, false);
        traced.record(500_000_000, false);

        TracedMethod.Snapshot snapshot = traced.snapshot();

        assertEquals(491_000_000, snapshot.p50Nanos());
        assertEquals(500_000_000, snapshot.p95Nanos());
        assertEquals(500_000_000, snapshot.p99Nanos());
    }

    @Test
    void neverReportsAPercentileAboveTheSlowestCall() {
        LongStream.rangeClosed(1, TracedMethod.RECENT_CALLS * 2L).forEach(call -> traced.record(1_000_000, false));

        TracedMethod.Snapshot snapshot = traced.snapshot();

        assertEquals(1_000_000, snapshot.maxNanos());
        assertEquals(1_000_000, snapshot.p99Nanos());
    }

    @Test
    void keepsTheLastCallsAndTheSlowestOnes() {
        long calls = TracedMethod.RECENT_CALLS + 30;
        LongStream.rangeClosed(1, calls).forEach(nanos -> traced.record(nanos * 1000, false));

        TracedMethod.Snapshot snapshot = traced.snapshot();

        assertEquals(TracedMethod.RECENT_CALLS, snapshot.recent().size());
        assertEquals(LongStream.rangeClosed(calls - TracedMethod.SLOWEST_CALLS + 1, calls).map(nanos -> nanos * 1000)
                        .boxed().sorted((a, b) -> Long.compare(b, a)).toList(),
                snapshot.slowest().stream().map(TracedCall::durationNanos).toList());
    }
}
