package me.fertiz.queryscope.agent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryScopeAgentTest {

    @Test
    void keepsTheApplicationRunningWhenAModuleIsMissing() {
        assertDoesNotThrow(() -> QueryScopeAgent.startOrTurnOff(() -> {
            throw new NoClassDefFoundError("com/sun/net/httpserver/HttpServer");
        }));
        assertDoesNotThrow(() -> QueryScopeAgent.startOrTurnOff(() -> {
            throw new IllegalStateException("instrumentation failed");
        }));
    }

    @Test
    void runsTheStartup() {
        AtomicBoolean started = new AtomicBoolean();

        QueryScopeAgent.startOrTurnOff(() -> started.set(true));

        assertTrue(started.get());
    }
}
