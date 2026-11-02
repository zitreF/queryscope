package me.fertiz.queryscope.agent.loadtest;

import me.fertiz.queryscope.agent.calltree.CallTree;
import me.fertiz.queryscope.agent.trace.UnitKind;
import me.fertiz.queryscope.agent.trace.UnitTrace;
import me.fertiz.queryscope.agent.trace.UnitTracer;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LoadTestPlanTest {

    @Test
    void replaysAGetRequestOnTheLoopbackPortItCameIn() {
        LoadTestPlan plan = LoadTestPlan.replaying(trace(UnitKind.HTTP, "GET", "/books/42?sort=title", 8080), 100, 10);

        assertEquals("GET /books/{id}", plan.endpoint());
        assertEquals(URI.create("http://127.0.0.1:8080/books/42?sort=title"), plan.target());
        assertEquals(10, plan.workers());
        assertEquals(3, LoadTestPlan.replaying(trace(UnitKind.HTTP, "GET", "/", 80), 3, 50).workers());
    }

    @Test
    void refusesWhatItCannotSendAgain() {
        assertEquals("QueryScope load tests GET requests only, request 5 was a POST", refusal(
                trace(UnitKind.HTTP, "POST", "/books", 8080)));
        assertEquals("Task ImportJob.run is no HTTP request, so it cannot be sent again", refusal(
                trace(UnitKind.TASK, "TASK", "ImportJob.run", UnitTracer.UNKNOWN_PORT)));
        assertEquals("QueryScope does not know the port request 5 came in on, so it cannot send it again", refusal(
                trace(UnitKind.HTTP, "GET", "/books", UnitTracer.UNKNOWN_PORT)));
        assertEquals("QueryScope cannot send @evil.example/books again to port 8080", refusal(
                trace(UnitKind.HTTP, "GET", "@evil.example/books", 8080)));
    }

    @Test
    void keepsTheNumbersWithinLimits() {
        UnitTrace books = trace(UnitKind.HTTP, "GET", "/books", 8080);

        assertEquals("requests needs a whole number from 1 to 10000, got 0", assertThrows(
                IllegalArgumentException.class, () -> LoadTestPlan.replaying(books, 0, 1)).getMessage());
        assertEquals("requests needs a whole number from 1 to 10000, got 10001", assertThrows(
                IllegalArgumentException.class, () -> LoadTestPlan.replaying(books, 10_001, 1)).getMessage());
        assertEquals("concurrency needs a whole number from 1 to 50, got 51", assertThrows(
                IllegalArgumentException.class, () -> LoadTestPlan.replaying(books, 10, 51)).getMessage());
    }

    private static String refusal(UnitTrace trace) {
        return assertThrows(IllegalArgumentException.class, () -> LoadTestPlan.replaying(trace, 10, 2)).getMessage();
    }

    private static UnitTrace trace(UnitKind kind, String method, String path, int port) {
        return new UnitTrace(5, kind, method, path, 200, Instant.now(), 1_000_000, List.of(), CallTree.EMPTY, 0, 1,
                0, 0, port, UnitTracer.NO_LOAD_TEST);
    }
}
