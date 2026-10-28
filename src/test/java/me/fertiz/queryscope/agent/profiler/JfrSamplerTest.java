package me.fertiz.queryscope.agent.profiler;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JfrSamplerTest {

    private static final Runnable FAILING_STREAM = () -> {
        throw new IllegalStateException("Event stream is closed");
    };

    @Test
    void fallsBackWhenTheStreamFailsToStart() {
        AtomicBoolean fellBack = new AtomicBoolean();

        JfrSampler.consume(FAILING_STREAM, new AtomicBoolean(false), () -> fellBack.set(true));

        assertTrue(fellBack.get());
    }

    @Test
    void ignoresAFailureWhileStopping() {
        AtomicBoolean fellBack = new AtomicBoolean();

        assertDoesNotThrow(() -> JfrSampler.consume(FAILING_STREAM, new AtomicBoolean(true), () -> fellBack.set(true)));

        assertFalse(fellBack.get());
    }
}
