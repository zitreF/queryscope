package me.fertiz.queryscope.agent.loadtest;

import me.fertiz.queryscope.agent.calltree.CallTree;
import me.fertiz.queryscope.agent.findings.FindingKind;
import me.fertiz.queryscope.agent.findings.NPlusOne;
import me.fertiz.queryscope.agent.findings.UnitReport;
import me.fertiz.queryscope.agent.trace.QueryExecution;
import me.fertiz.queryscope.agent.trace.UnitKind;
import me.fertiz.queryscope.agent.trace.UnitTrace;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.URI;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadTesterTest {

    private static final URI TARGET = URI.create("http://127.0.0.1:8080/books/1");
    private static final long SERVER_SIDE_WAIT = TimeUnit.SECONDS.toNanos(2);
    private static final String BY_AUTHOR = "select * from books where author_id = ?";

    private final CompletableFuture<LoadTestResult> finished = new CompletableFuture<>();

    @Test
    void sendsEveryRequestFromItsOwnThreadsAndCountsTheStatuses() throws Exception {
        Set<String> threads = ConcurrentHashMap.newKeySet();
        AtomicInteger sent = new AtomicInteger();
        LoadTester tester = tester((target, run) -> {
            threads.add(Thread.currentThread().getName());
            return sent.incrementAndGet() % 10 == 0 ? 500 : 200;
        });

        tester.start(plan(100, 5));
        LoadTestResult result = finished.get(10, TimeUnit.SECONDS);

        assertEquals(LoadTestResult.State.FINISHED, result.state());
        assertEquals(100, result.answered());
        assertEquals(0, result.failed());
        assertEquals(Map.of(200, 90, 500, 10), result.statuses());
        assertEquals(10, result.errorStatuses());
        assertTrue(threads.size() <= 5, threads.toString());
        assertTrue(threads.stream().allMatch(name -> name.startsWith("queryscope-load-test-1-")), threads.toString());
        assertTrue(result.summary().startsWith("QueryScope load test of GET /books/{id} finished: 100 of 100 requests"
                + " answered in "), result.summary());
        assertTrue(result.summary().endsWith(", 10 answered with an error status"), result.summary());
    }

    @Test
    void namesTheFirstErrorOfRequestsWithoutAnswer() throws Exception {
        LoadTester tester = tester((target, run) -> {
            throw new ConnectException("Connection refused");
        });

        tester.start(plan(4, 2));
        LoadTestResult result = finished.get(10, TimeUnit.SECONDS);

        assertEquals(0, result.answered());
        assertEquals(4, result.failed());
        assertEquals("ConnectException: Connection refused", result.firstError());
        assertTrue(result.summary().endsWith(", 4 got no answer: ConnectException: Connection refused"),
                result.summary());
    }

    @Test
    void addsUpTheServerSideOfItsOwnRequestsOnly() throws Exception {
        AtomicInteger sent = new AtomicInteger();
        LoadTester[] holder = new LoadTester[1];
        LoadTester tester = tester((target, run) -> {
            int queries = sent.incrementAndGet() % 2 == 0 ? 3 : 5;
            holder[0].record(report(run, queries, true));
            holder[0].record(report(run + 1, 40, true));
            return 200;
        });
        holder[0] = tester;

        tester.start(plan(10, 2));
        LoadTestResult result = finished.get(10, TimeUnit.SECONDS);

        assertEquals(10, result.tracedRequests());
        assertEquals(4.0, result.averageQueries());
        assertEquals(3, result.fewestQueries());
        assertEquals(5, result.mostQueries());
        assertEquals(2_400_000, result.averageDatabaseNanos());
        assertEquals(1, result.findings().size());
        LoadTestResult.FindingCount nPlusOne = result.findings().get(0);
        assertEquals(FindingKind.N_PLUS_ONE, nPlusOne.kind());
        assertEquals(10, nPlusOne.requests());
        assertTrue(nPlusOne.example().startsWith("N+1: "), nPlusOne.example());
    }

    @Test
    void waitsBrieflyForTracesThatArriveAfterTheAnswer() throws Exception {
        LoadTester[] holder = new LoadTester[1];
        LoadTester tester = tester((target, run) -> {
            CompletableFuture.runAsync(() -> {
                pause(100);
                holder[0].record(report(run, 1, false));
            });
            return 200;
        }, SERVER_SIDE_WAIT);
        holder[0] = tester;

        tester.start(plan(3, 3));
        LoadTestResult result = finished.get(10, TimeUnit.SECONDS);

        assertEquals(3, result.tracedRequests());
    }

    @Test
    void runsOneTestAtATimeAndStopsWhenAsked() throws Exception {
        CountDownLatch sending = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        LoadTester tester = tester((target, run) -> {
            sending.countDown();
            awaitQuietly(release);
            return 200;
        });

        tester.start(plan(1_000, 2));
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> tester.start(plan(5, 1)));
        assertTrue(sending.await(10, TimeUnit.SECONDS));
        assertTrue(tester.stop());
        release.countDown();
        LoadTestResult result = finished.get(10, TimeUnit.SECONDS);

        assertEquals("QueryScope is still load testing GET /books/{id}, stop that test first", refused.getMessage());
        assertEquals(LoadTestResult.State.STOPPED, result.state());
        assertEquals(2, result.answered());
        assertFalse(tester.stop());
    }

    @Test
    void recognizesOnlyRequestsOfRunsItStarted() throws Exception {
        LoadTester tester = tester((target, run) -> 200);

        assertFalse(tester.sent(report(1, 0, false).trace()));
        tester.start(plan(1, 1));
        finished.get(10, TimeUnit.SECONDS);

        assertTrue(tester.sent(report(1, 0, false).trace()));
        assertFalse(tester.sent(report(0, 0, false).trace()));
        assertFalse(tester.sent(report(2, 0, false).trace()));
    }

    @Test
    void readsPercentilesByRank() {
        LoadTestResult.Latency latency = LoadTestResult.Latency.of(new long[]{50, 10, 40, 20, 30, 0}, 5);

        assertEquals(new LoadTestResult.Latency(30, 30, 50, 50, 50), latency);
        assertEquals(LoadTestResult.Latency.NONE, LoadTestResult.Latency.of(new long[3], 0));
    }

    private LoadTester tester(RequestSender sender) {
        return tester(sender, 0);
    }

    private LoadTester tester(RequestSender sender, long serverSideWaitNanos) {
        return new LoadTester(sender, finished::complete, serverSideWaitNanos);
    }

    private static LoadTestPlan plan(int requests, int concurrency) {
        return new LoadTestPlan("GET /books/{id}", TARGET, requests, concurrency);
    }

    private static UnitReport report(long run, int queries, boolean nPlusOne) {
        List<QueryExecution> executions = IntStream.range(0, queries)
                .mapToObj(index -> new QueryExecution(BY_AUTHOR, "[" + index + "]", index, 600_000, 1,
                        "com.acme.Books.list(Books.java:12)", false))
                .toList();
        UnitTrace trace = new UnitTrace(run * 100, UnitKind.HTTP, "GET", "/books/1", 200, Instant.now(), 9_000_000,
                executions, CallTree.EMPTY, 0, 1, 0, 0, 8080, run);
        return new UnitReport(trace, nPlusOne
                ? List.of(new NPlusOne(BY_AUTHOR, "com.acme.Books.list(Books.java:12)",
                        IntStream.range(0, queries).boxed().toList(), 2_000_000))
                : Collections.emptyList());
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
