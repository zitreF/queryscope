package me.fertiz.queryscope.agent.profiler;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class SampleStoreTest {

    private static final Instant NOW = Instant.parse("2026-10-28T10:00:00Z");
    private static final List<String> STACK = List.of("java.lang.Thread.run", "com.acme.Job.run");

    private final SampleStore store = new SampleStore(Clock.fixed(NOW, ZoneOffset.UTC), 3, Duration.ofMinutes(1));

    @Test
    void keepsTheNewestSamplesWithinCapacityAndAge() {
        store.add(NOW.minusSeconds(61).toEpochMilli(), 1, "old", true, false, STACK);
        for (int second = 4; second >= 1; second--) {
            store.add(NOW.minusSeconds(second).toEpochMilli(), 1, "worker", true, false, STACK);
        }

        List<Sample> samples = store.between(0, Long.MAX_VALUE);

        assertEquals(List.of(NOW.minusSeconds(3).toEpochMilli(), NOW.minusSeconds(2).toEpochMilli(),
                NOW.minusSeconds(1).toEpochMilli()), samples.stream().map(Sample::epochMillis).toList());
    }

    @Test
    void sharesIdenticalStacksAndNamesMethodsAndThreads() {
        store.add(NOW.toEpochMilli(), 7, "http-nio-8080-exec-1", true, false, STACK);
        store.add(NOW.toEpochMilli(), 7, "http-nio-8080-exec-1", false, false, STACK);

        List<Sample> samples = store.between(NOW.toEpochMilli(), NOW.toEpochMilli());

        assertSame(samples.get(0).frames(), samples.get(1).frames());
        assertEquals("com.acme.Job.run", store.method(samples.get(0).frames()[1]));
        assertEquals("http-nio-8080-exec-1", store.threadName(7));
        assertEquals("thread 8", store.threadName(8));
    }
}
