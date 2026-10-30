package me.fertiz.queryscope.agent.methodtrace;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LatencyHistogramTest {

    @Test
    void keepsSmallDurationsExact() {
        for (long nanos = 1; nanos < 8; nanos++) {
            assertEquals(nanos, LatencyHistogram.middleOf(LatencyHistogram.bucketOf(nanos)));
        }
    }

    @Test
    void placesEveryDurationWithinAnEighthOfItsBucket() {
        for (long nanos = 8; nanos < TimeUnit.SECONDS.toNanos(100); nanos = nanos * 3 / 2 + 1) {
            long middle = LatencyHistogram.middleOf(LatencyHistogram.bucketOf(nanos));
            assertTrue(Math.abs(middle - nanos) <= nanos / 8, nanos + " landed in a bucket around " + middle);
        }
    }

    @Test
    void reportsPercentilesOfTheRecordedDurations() {
        LatencyHistogram histogram = new LatencyHistogram();
        for (int millis = 1; millis <= 100; millis++) {
            histogram.record(TimeUnit.MILLISECONDS.toNanos(millis));
        }

        assertAbout(50, histogram.percentile(0.5));
        assertAbout(95, histogram.percentile(0.95));
        assertAbout(100, histogram.percentile(1.0));
        assertEquals(0, new LatencyHistogram().percentile(0.5));
    }

    private static void assertAbout(long expectedMillis, long nanos) {
        double millis = nanos / 1_000_000.0;
        assertTrue(Math.abs(millis - expectedMillis) <= expectedMillis / 8.0, "expected about " + expectedMillis
                + " ms, got " + millis);
    }
}
