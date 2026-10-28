package me.fertiz.queryscope.agent.findings;

import me.fertiz.queryscope.agent.calltree.CallNode;
import me.fertiz.queryscope.agent.calltree.CallTree;
import me.fertiz.queryscope.agent.trace.QueryExecution;
import me.fertiz.queryscope.agent.trace.RequestTrace;
import me.fertiz.queryscope.agent.trace.UnitKind;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryAnalyzerTest {

    private static final String SERVICE = "com.acme.OrderService.summaries(OrderService.java:20)";
    private static final String MAPPER = "com.acme.OrderMapper.toDto(OrderMapper.java:12)";

    private final QueryAnalyzer analyzer = new QueryAnalyzer(new Thresholds(3, Duration.ofMillis(100), 500, 10,
            Duration.ofMillis(50)));

    @Test
    void reportsSelectRepeatedWithDifferentLiterals() {
        List<QueryExecution> queries = new ArrayList<>();
        queries.add(query("select * from customers", SERVICE, 2));
        IntStream.rangeClosed(1, 4)
                .mapToObj(id -> query("select * from orders where customer_id = " + id, MAPPER, 1))
                .forEach(queries::add);

        List<Finding> findings = analyze(queries);

        assertEquals(List.of(new NPlusOne("select * from orders where customer_id = ?", MAPPER,
                List.of(1, 2, 3, 4), Duration.ofMillis(4).toNanos())), findings);
    }

    @Test
    void namesTheCallSiteThatRanMostOfTheRepeats() {
        List<QueryExecution> queries = List.of(
                query("select * from orders where customer_id = ?", SERVICE, 1),
                query("select * from orders where customer_id = ?", MAPPER, 1),
                query("select * from orders where customer_id = ?", MAPPER, 1));

        NPlusOne nPlusOne = assertInstanceOf(NPlusOne.class, analyze(queries).get(0));

        assertEquals(MAPPER, nPlusOne.callSite());
    }

    @Test
    void staysQuietBelowThreshold() {
        List<QueryExecution> queries = List.of(
                query("select * from orders where customer_id = 1", MAPPER, 1),
                query("select * from orders where customer_id = 2", MAPPER, 1));

        assertTrue(analyze(queries).isEmpty());
    }

    @Test
    void reportsRepeatedWritesAsUnbatched() {
        List<QueryExecution> queries = IntStream.rangeClosed(1, 4)
                .mapToObj(id -> query("insert into orders (id) values (" + id + ")", SERVICE, 1))
                .toList();

        assertEquals(List.of(new RepeatedWrite("insert into orders (id) values (?)", SERVICE,
                List.of(0, 1, 2, 3), Duration.ofMillis(4).toNanos())), analyze(queries));
    }

    @Test
    void reportsTheSameQueryWithTheSameParametersRunTwice() {
        List<QueryExecution> queries = List.of(
                query("select name from customers where id = ?", "[7]", MAPPER),
                query("select email from customers where id = ?", "[7]", MAPPER),
                query("select name from customers where id = ?", "[7]", SERVICE));

        assertEquals(List.of(new DuplicateQuery("select name from customers where id = ?", "[7]", MAPPER,
                List.of(0, 2))), analyze(queries));
    }

    @Test
    void doesNotRepeatAnNPlusOneAsDuplicates() {
        List<QueryExecution> queries = IntStream.range(0, 3)
                .mapToObj(index -> query("select * from orders where customer_id = ?", "[1]", MAPPER))
                .toList();

        assertEquals(List.of(NPlusOne.class), analyze(queries).stream().map(Object::getClass).toList());
    }

    @Test
    void reportsQueriesAtOrAboveSlowThreshold() {
        List<QueryExecution> queries = List.of(
                query("select count(*) from audit_events", SERVICE, 99),
                query("select type, count(*) from audit_events group by type", SERVICE, 100));

        assertEquals(List.of(new SlowQuery(1, "select type, count(*) from audit_events group by type", queries.get(1))), analyze(queries));
    }

    @Test
    void reportsQueriesThatReturnManyRows() {
        QueryExecution small = new QueryExecution("select * from orders", "", 0, 1_000, 499, SERVICE, false);
        QueryExecution large = new QueryExecution("select * from audit_events", "", 0, 1_000, 500, SERVICE, false);

        assertEquals(List.of(new LargeResult(1, "select * from audit_events", large)), analyze(List.of(small, large)));
    }

    @Test
    void reportsRequestsWithTooManyQueries() {
        List<QueryExecution> queries = IntStream.range(0, 10)
                .mapToObj(index -> query("select * from table_" + (char) ('a' + index), SERVICE, 2))
                .toList();

        List<Finding> findings = analyze(queries);

        assertEquals(List.of(new TooManyQueries(10, 10, Duration.ofMillis(20).toNanos(), Duration.ofMillis(50).toNanos())),
                findings);
    }

    @Test
    void reportsMethodsThatSpendTooLongInTheirOwnCodeOutsideSql() {
        List<QueryExecution> queries = List.of(query("select * from orders", SERVICE, 30));
        CallNode mapperInService = node("com.acme.OrderMapper.toDto", 20, 40, 40, false, List.of());
        CallNode service = node("com.acme.OrderService.summaries", 1, 200, 140, false, List.of(0), mapperInService);
        CallNode mapperInJob = node("com.acme.OrderMapper.toDto", 10, 30, 30, false, List.of());
        CallNode job = node("com.acme.ImportJob.run", 1, 330, 100, true, List.of(), service, mapperInJob);
        RequestTrace trace = new RequestTrace(1, UnitKind.TASK, "TASK", "ImportJob.run", 0, Instant.now(),
                Duration.ofMillis(330).toNanos(), queries, new CallTree(List.of(job), List.of(), false), 0, 1);

        List<Finding> findings = analyzer.analyze(trace).findings();

        assertEquals(List.of(
                new SlowMethod("com.acme.OrderService.summaries", 1, Duration.ofMillis(110).toNanos(),
                        Duration.ofMillis(330).toNanos()),
                new SlowMethod("com.acme.OrderMapper.toDto", 30, Duration.ofMillis(70).toNanos(),
                        Duration.ofMillis(330).toNanos())), findings);
    }

    @Test
    void leavesSlowMethodsOutWhileTheJvmIsStillWarmingUp() {
        CallNode render = node("com.acme.ReportService.render", 1, 400, 400, false, List.of());
        RequestTrace trace = new RequestTrace(1, UnitKind.HTTP, "GET", "/report", 200, Instant.now(),
                Duration.ofMillis(400).toNanos(), List.of(), new CallTree(List.of(render), List.of(), false),
                RequestTrace.WARM_UP_CLASSES, 1);

        assertEquals(List.of(), analyzer.analyze(trace).findings());
    }

    private List<Finding> analyze(List<QueryExecution> queries) {
        RequestTrace trace = new RequestTrace(1, UnitKind.HTTP, "GET", "/orders", 200, Instant.now(),
                Duration.ofMillis(50).toNanos(), queries);
        return analyzer.analyze(trace).findings();
    }

    private static CallNode node(String method, long calls, long totalMillis, long selfMillis, boolean startedBefore,
                                 List<Integer> queryIndexes, CallNode... children) {
        return new CallNode(method, calls, Duration.ofMillis(totalMillis).toNanos(),
                Duration.ofMillis(selfMillis).toNanos(), startedBefore, queryIndexes, List.of(children));
    }

    private static QueryExecution query(String sql, String callSite, long millis) {
        return new QueryExecution(sql, "", 0, Duration.ofMillis(millis).toNanos(), QueryExecution.NO_RESULT,
                callSite, false);
    }

    private static QueryExecution query(String sql, String parameters, String callSite) {
        return new QueryExecution(sql, parameters, 0, 1_000, 1, callSite, false);
    }
}
