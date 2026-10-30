package me.fertiz.queryscope.agent.console;

import me.fertiz.queryscope.agent.appcode.AppPackages;
import me.fertiz.queryscope.agent.findings.DuplicateQuery;
import me.fertiz.queryscope.agent.findings.Finding;
import me.fertiz.queryscope.agent.findings.NPlusOne;
import me.fertiz.queryscope.agent.findings.UnitReport;
import me.fertiz.queryscope.agent.profiler.Profile;
import me.fertiz.queryscope.agent.profiler.SampleStore;
import me.fertiz.queryscope.agent.trace.QueryExecution;
import me.fertiz.queryscope.agent.trace.UnitTrace;
import me.fertiz.queryscope.agent.trace.UnitKind;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionSummaryTest {

    private static final Instant STARTED_AT = Instant.parse("2026-10-27T09:00:00Z");
    private static final String MAPPER = "com.acme.OrderMapper.toDto(OrderMapper.java:12)";
    private static final NPlusOne ORDERS = new NPlusOne("select * from orders where customer_id = ?", MAPPER,
            List.of(0, 1, 2, 3, 4), 5_000_000);

    private final TestClock clock = new TestClock();
    private final SessionSummary summary = new SessionSummary(clock);
    private final SampleStore samples = new SampleStore(clock, 100, Duration.ofHours(1));

    @Test
    void printsNothingWhenNoQueryRan() {
        assertEquals("", print());
    }

    @Test
    void groupsRequestsByEndpointWithIdentifiersReplaced() {
        summary.accept(http("/api/customers/1/orders", 3, List.of()));
        summary.accept(http("/api/customers/2/orders?page=1", 5, List.of()));
        summary.accept(http("/api/orders/5f0c8a3e-9d1b-4c47-8a55-0f6b2a7d9e11", 1, List.of()));
        summary.accept(task("DigestJob.run", 2));
        clock.advance(Duration.ofSeconds(125));

        String output = print();

        assertTrue(output.contains("QueryScope session summary: 2 min 5 s, 4 requests and tasks, 11 queries"), output);
        assertTrue(output.matches("(?s).*GET /api/customers/\\{id}/orders +2 +4\\.0 .*"), output);
        assertTrue(output.contains("GET /api/orders/{uuid}"), output);
        assertTrue(output.contains("Task DigestJob.run"), output);
        assertTrue(output.contains("No findings."), output);
    }

    @Test
    void countsTheSameFindingOnceForEveryRequestItAppearsIn() {
        summary.accept(http("/api/customers/1/orders", 6, List.of(ORDERS)));
        summary.accept(http("/api/customers/2/orders", 6, List.of(ORDERS)));
        summary.accept(http("/api/customers/3/orders", 6, List.of(ORDERS)));

        String output = print();

        assertTrue(output.contains("3 x  N+1: 5 x select * from orders where customer_id = ? at " + MAPPER
                + "  (GET /api/customers/{id}/orders)"), output);
        assertTrue(output.matches("(?s).*GET /api/customers/\\{id}/orders +3 +6\\.0 .* N\\+1\\R.*"), output);
    }

    @Test
    void mentionsTasksThatWereStillOpenWhenTheJvmStopped() {
        assertTrue(print(1).contains("  1 task was still open when the JVM stopped and is not counted"), print(1));
        assertTrue(print(3).contains("  3 tasks were still open when the JVM stopped and are not counted"), print(3));
        assertFalse(print(0).contains("still open"));
    }

    @Test
    void countsADuplicateQueryOnceWhateverItsParameters() {
        String sql = "select * from customers where id = ?";
        DuplicateQuery seven = new DuplicateQuery(sql, "[7]", MAPPER, List.of(0, 1));
        DuplicateQuery eight = new DuplicateQuery(sql, "[8]", MAPPER, List.of(0, 1));
        summary.accept(http("/api/customers/7/card", 2, List.of(seven)));
        summary.accept(http("/api/customers/8/card", 2, List.of(eight)));

        String output = print();

        assertTrue(output.contains("2 x  Duplicate query: 2 x " + sql + " at " + MAPPER), output);
        assertFalse(output.contains("[7]"), output);
    }

    @Test
    void listsTheHottestMethodsEvenWhenNoQueryRan() {
        List<String> render = List.of("java.lang.Thread.run", "com.acme.Report.render", "java.lang.StringBuilder.append");
        for (int index = 0; index < 3; index++) {
            samples.add(STARTED_AT.toEpochMilli(), 1, "main", true, false, render);
        }
        samples.add(STARTED_AT.toEpochMilli(), 1, "main", true, false, List.of("java.lang.Thread.run", "com.acme.Report.load"));

        String output = print();

        assertTrue(output.contains("QueryScope session summary: 0 s, 0 requests and tasks, 0 queries, 4 CPU samples"), output);
        assertTrue(output.contains(" 75.0%  75.0%  java.lang.StringBuilder.append"), output);
        assertTrue(output.contains(" 25.0%  25.0%  com.acme.Report.load  (your code)"), output);
        assertTrue(output.contains("Your methods by CPU time in them and in the library code they call"), output);
        assertTrue(output.contains(" 75.0%  com.acme.Report.render"), output);
    }

    private String print() {
        return print(0);
    }

    private String print(int openTasks) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Profile profile = Profile.build(samples, AppPackages.of(List.of("com.acme")), Profile.Filter.allSamples());
        summary.print(new PrintStream(bytes, true, StandardCharsets.UTF_8), profile, openTasks);
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static UnitReport http(String path, int queries, List<Finding> findings) {
        return new UnitReport(new UnitTrace(1, UnitKind.HTTP, "GET", path, 200, STARTED_AT, 10_000_000,
                queries(queries)), findings);
    }

    private static UnitReport task(String name, int queries) {
        return new UnitReport(new UnitTrace(2, UnitKind.TASK, null, name, 0, STARTED_AT, 4_000_000,
                queries(queries)), List.of());
    }

    private static List<QueryExecution> queries(int count) {
        return IntStream.range(0, count)
                .mapToObj(index -> new QueryExecution("select * from orders where customer_id = " + index, "",
                        index * 1_000_000L, 1_000_000, 1, MAPPER, false))
                .toList();
    }

    private static final class TestClock extends Clock {

        private Instant now = STARTED_AT;

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
