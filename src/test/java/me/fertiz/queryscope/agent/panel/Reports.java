package me.fertiz.queryscope.agent.panel;

import me.fertiz.queryscope.agent.calltree.CallTree;
import me.fertiz.queryscope.agent.findings.NPlusOne;
import me.fertiz.queryscope.agent.findings.UnitReport;
import me.fertiz.queryscope.agent.findings.SlowQuery;
import me.fertiz.queryscope.agent.trace.QueryExecution;
import me.fertiz.queryscope.agent.trace.UnitTrace;
import me.fertiz.queryscope.agent.trace.UnitKind;
import me.fertiz.queryscope.agent.trace.UnitTracer;

import java.time.Instant;
import java.util.List;

final class Reports {

    static final Instant STARTED_AT = Instant.parse("2026-10-25T20:00:00Z");

    private Reports() {
    }

    static UnitReport plain(long id, String path) {
        QueryExecution query = new QueryExecution("select 1", "", 500_000, 250_000, 1, null, false);
        UnitTrace trace = new UnitTrace(id, UnitKind.HTTP, "GET", path, 200, STARTED_AT, 2_000_000, List.of(query));
        return new UnitReport(trace, List.of());
    }

    static UnitReport servedOnPort(long id, String path, int port) {
        UnitTrace trace = new UnitTrace(id, UnitKind.HTTP, "GET", path, 200, STARTED_AT, 2_000_000, List.of(),
                CallTree.EMPTY, 0, 1, 0, 0, port, UnitTracer.NO_LOAD_TEST);
        return new UnitReport(trace, List.of());
    }

    static UnitReport withoutSql(long id, String path) {
        return new UnitReport(new UnitTrace(id, UnitKind.HTTP, "GET", path, 200, STARTED_AT, 1_000_000, List.of()),
                List.of());
    }

    static UnitReport withFindings() {
        QueryExecution first = new QueryExecution("select * from orders where customer_id = 1", "", 1_000_000,
                500_000, 3, "com.acme.Mapper.toDto(Mapper.java:7)", false);
        QueryExecution second = new QueryExecution("select * from orders where customer_id = 2", "", 2_000_000,
                120_000_000, QueryExecution.NO_RESULT, "com.acme.Mapper.toDto(Mapper.java:7)", true);
        UnitTrace trace = new UnitTrace(7, UnitKind.HTTP, "GET", "/customers?q=\"x\"", 500, STARTED_AT, 125_000_000,
                List.of(first, second));
        return new UnitReport(trace, List.of(
                new NPlusOne("select * from orders where customer_id = ?", "com.acme.Mapper.toDto(Mapper.java:7)",
                        List.of(0, 1), 120_500_000),
                new SlowQuery(1, "select * from orders where customer_id = ?", second)));
    }
}
