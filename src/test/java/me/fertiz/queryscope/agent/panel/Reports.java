package me.fertiz.queryscope.agent.panel;

import me.fertiz.queryscope.agent.data.NPlusOne;
import me.fertiz.queryscope.agent.data.QueryExecution;
import me.fertiz.queryscope.agent.data.RequestReport;
import me.fertiz.queryscope.agent.data.RequestTrace;
import me.fertiz.queryscope.agent.data.SlowQuery;

import java.time.Instant;
import java.util.List;

final class Reports {

    static final Instant STARTED_AT = Instant.parse("2026-10-25T20:00:00Z");

    private Reports() {
    }

    static RequestReport plain(long id, String path) {
        QueryExecution query = new QueryExecution("select 1", 500_000, 250_000, null, false);
        return new RequestReport(new RequestTrace(id, "GET", path, 200, STARTED_AT, 2_000_000, List.of(query)), List.of());
    }

    static RequestReport withFindings() {
        QueryExecution first = new QueryExecution("select * from orders where customer_id = 1", 1_000_000, 500_000,
                "com.acme.Mapper.toDto(Mapper.java:7)", false);
        QueryExecution second = new QueryExecution("select * from orders where customer_id = 2", 2_000_000, 120_000_000,
                "com.acme.Mapper.toDto(Mapper.java:7)", true);
        RequestTrace trace = new RequestTrace(7, "GET", "/customers?q=\"x\"", 500, STARTED_AT, 125_000_000,
                List.of(first, second));
        return new RequestReport(trace, List.of(
                new NPlusOne("select * from orders where customer_id = ?", "com.acme.Mapper.toDto(Mapper.java:7)",
                        List.of(0, 1), 120_500_000),
                new SlowQuery(1, second)));
    }
}
