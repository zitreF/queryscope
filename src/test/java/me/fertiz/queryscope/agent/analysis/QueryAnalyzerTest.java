package me.fertiz.queryscope.agent.analysis;

import me.fertiz.queryscope.agent.data.Finding;
import me.fertiz.queryscope.agent.data.NPlusOne;
import me.fertiz.queryscope.agent.data.QueryExecution;
import me.fertiz.queryscope.agent.data.RequestTrace;
import me.fertiz.queryscope.agent.data.SlowQuery;
import me.fertiz.queryscope.agent.data.UnitKind;
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

    private final QueryAnalyzer analyzer = new QueryAnalyzer(3, Duration.ofMillis(100));

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
    void repeatedWritesAreNoNPlusOne() {
        List<QueryExecution> queries = IntStream.rangeClosed(1, 5)
                .mapToObj(id -> query("insert into orders (id) values (" + id + ")", SERVICE, 1))
                .toList();

        assertTrue(analyze(queries).isEmpty());
    }

    @Test
    void reportsQueriesAtOrAboveSlowThreshold() {
        List<QueryExecution> queries = List.of(
                query("select count(*) from audit_events", SERVICE, 99),
                query("select type, count(*) from audit_events group by type", SERVICE, 100));

        List<Finding> findings = analyze(queries);

        assertEquals(List.of(new SlowQuery(1, queries.get(1))), findings);
    }

    private List<Finding> analyze(List<QueryExecution> queries) {
        RequestTrace trace = new RequestTrace(1, UnitKind.HTTP, "GET", "/orders", 200, Instant.now(), 0, queries);
        return analyzer.analyze(trace).findings();
    }

    private static QueryExecution query(String sql, String callSite, long millis) {
        return new QueryExecution(sql, 0, Duration.ofMillis(millis).toNanos(), callSite, false);
    }
}
