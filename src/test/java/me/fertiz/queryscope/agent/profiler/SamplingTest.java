package me.fertiz.queryscope.agent.profiler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SamplingTest {

    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final String BUSY = SamplingTest.class.getName() + ".spin";

    private final SampleStore store = new SampleStore(Clock.systemUTC(), 100_000, Duration.ofMinutes(5));
    private final AtomicBoolean running = new AtomicBoolean(true);
    private volatile long sink;

    @AfterEach
    void stopSpinning() {
        running.set(false);
    }

    @Test
    void flightRecorderSamplesABusyThread() throws InterruptedException {
        startSpinning();

        Sampling sampling = Sampling.start(store, Duration.ofMillis(10));
        try {
            assertTrue(sampled(), "No sample of " + BUSY + " within " + WAIT);
            assertEquals(JfrSampler.SOURCE, store.source());
        } finally {
            sampling.stop();
        }
    }

    @Test
    void threadDumpsSampleABusyThreadWhenThereIsNoFlightRecorder() throws InterruptedException {
        startSpinning();

        Instant deadline = Instant.now().plus(WAIT);
        while (!contains() && Instant.now().isBefore(deadline)) {
            ThreadDumpSampler.sample(store);
            Thread.sleep(10);
        }

        assertTrue(contains(), "No sample of " + BUSY);
    }

    @Test
    void dropsTheAddressOfHiddenClasses() {
        assertEquals("java.lang.invoke.LambdaForm$MH", Sampling.typeName("java.lang.invoke.LambdaForm$MH/0x000001f0"));
        assertEquals("java.lang.String$$StringConcat", Sampling.typeName("java.lang.String$$StringConcat.0x000001f0"));
        assertEquals("com.acme.Job", Sampling.typeName("com.acme.Job"));
    }

    private void startSpinning() {
        Thread spinner = new Thread(this::spin, "busy-spinner");
        spinner.setDaemon(true);
        spinner.start();
    }

    private void spin() {
        long value = 0;
        while (running.get()) {
            value = value * 31 + System.nanoTime() % 7;
            sink = value;
        }
    }

    private boolean sampled() throws InterruptedException {
        Instant deadline = Instant.now().plus(WAIT);
        while (Instant.now().isBefore(deadline)) {
            if (contains()) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    private boolean contains() {
        return store.between(0, Long.MAX_VALUE).stream()
                .anyMatch(sample -> Arrays.stream(sample.frames()).mapToObj(store::method).anyMatch(BUSY::equals));
    }
}
